package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.mock.env.MockEnvironment;

import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyProblem;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyResolver;
import top.sywyar.pixivdownload.plugin.install.PluginInstallService;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactLoadPlan;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimePackagePhase;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HeadlessDependencyInstallationTest {
    @TempDir Path root;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"hot-reload", "process-restart"})
    void installAndRestartWithRequiredDesktopPackage(String policy) throws Exception {
        Path desktop = PluginTestProvenance.stageRepairProbe(root, "fixture-desktop", "1.1.1",
                "pixiv.desktop-only=true\npixiv.lifecycle-policy=process-restart");
        exerciseInstallation(desktop, policy);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "test.compose.artifact", matches = ".+")
    void installAndRestartWithPackagedCompose() throws Exception {
        exerciseInstallation(Path.of(System.getProperty("test.compose.artifact")), "hot-reload");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "test.compose.universal", matches = ".+")
    void restartPredictionReusesTheCurrentInventoryDigest() throws Exception {
        var desktop = Path.of(System.getProperty("test.compose.universal"));
        var plugins = root.resolve("plugins");
        try (var session = start(plugins)) {
            var services = new Services(session);
            var id = PluginPackageReader.inspect(desktop).descriptor().id();
            assertThat(services.install.installTrustedFile(desktop, false, origin(desktop)).accepted()).isTrue();
            Path jar = session.installer().listInstalled().getFirst().path().toAbsolutePath();
            long size = java.nio.file.Files.size(jar);
            services.management.list();
            long standalone = fileReadBytes(jar, () -> assertThat(services.entry(id).effectiveAfterRestart()).isFalse());
            var consumer = PluginTestProvenance.stageRepairProbe(root, "awaiting", "1.0.0", "plugin.dependencies=" + id);
            assertThat(services.install.installTrustedFile(consumer, false, origin(consumer)).effectiveAfterRestart()).isTrue();
            services.management.list();
            long pending = fileReadBytes(jar, () -> assertThat(services.entry(id).effectiveAfterRestart()).isTrue());
            var inventory = session.installer().snapshotInstalledWithProvenance(InstalledPluginInventorySnapshot.MAX_RECORDS,
                    InstalledPluginInventorySnapshot.MAX_PROVENANCE_BYTES).entries();
            long projection = fileReadBytes(jar, () -> assertThat(services.coordinator.pendingInstalls(inventory)).contains(id));
            System.out.printf("Compose file reads: size=%d standalone=%d pending=%d prediction=%d%n", size, standalone, pending, projection);
            assertThat(standalone).isEqualTo(size);
            assertThat(pending).isEqualTo(standalone);
            assertThat(projection).isZero();

            var installedConsumer = session.installer().listInstalled().stream()
                    .filter(item -> item.id().equals("awaiting")).findFirst().orElseThrow().path();
            var original = Files.readAllBytes(installedConsumer);
            var altered = original.clone();
            altered[10] ^= 1; // 修改 ZIP 时间戳，保持文件大小和描述符不变。
            var timestamp = Files.getLastModifiedTime(installedConsumer);
            Files.write(installedConsumer, altered);
            Files.setLastModifiedTime(installedConsumer, timestamp);
            assertThat(services.entry(id).effectiveAfterRestart()).isFalse();
            Files.write(installedConsumer, original);
            Files.setLastModifiedTime(installedConsumer, timestamp);
            assertThat(services.entry(id).effectiveAfterRestart()).isTrue();
            services.management.revokeTrust("awaiting");
            assertThat(services.entry(id).effectiveAfterRestart()).isFalse();
        }
    }

    private long fileReadBytes(Path jar, Runnable query) throws Exception {
        var file = java.nio.file.Files.createTempFile(root, "reads-", ".jfr");
        try (var recording = new jdk.jfr.Recording()) {
            recording.enable("jdk.FileRead").withThreshold(java.time.Duration.ZERO).withoutStackTrace();
            recording.start();
            query.run();
            recording.stop();
            recording.dump(file);
        }
        long total = 0;
        try (var events = new jdk.jfr.consumer.RecordingFile(file)) {
            while (events.hasMoreEvents()) {
                var event = events.readEvent();
                if (event.getEventType().getName().equals("jdk.FileRead") && jar.toString().equals(event.getString("path"))) {
                    total += Math.max(0, event.getLong("bytesRead"));
                }
            }
        }
        return total;
    }

    private void exerciseInstallation(Path desktop, String policy) throws Exception {
        var plan = PluginArtifactLoadPlan.create(List.of(desktop));
        assertThat(plan.failures()).isEmpty();
        assertThat(plan.headlessEntries()).isEmpty();
        String id = plan.orderedEntries().getFirst().pluginId();
        var plugins = root.resolve("plugins");
        var enabled = PluginEnabledSnapshot.empty();
        try (var session = PluginBootstrapSession.createContext(plugins, enabled).start()) {
            var coordinator = new ExternalPluginLifecycleCoordinator(session.manager(), mock(PluginLifecycleService.class),
                    session.installer(), mock(RecoveryModeService.class), new PluginDependencyResolver(session.installer()));
            assertThat(coordinator.installOrUpdate(desktop, false, origin(desktop)).installResult().accepted()).isTrue();
        }

        TestPropertyValues.of(RuntimeFiles.PLUGINS_DIR_PROPERTY + "=" + plugins,
                RuntimeFiles.STATE_DIR_PROPERTY + "=" + root.resolve("state")).applyToSystemProperties(() -> {
            try (var tool = DatabaseBootstrap.openToolSession(new MockEnvironment())) {
                assertThat(tool.ownership()).isEqualTo(PluginBootstrapSession.Ownership.PROCESS);
                tool.closeForContext();
                assertThat(tool.status().failures()).isEmpty();
                assertThat(tool.status().startedPluginIds()).isEmpty();
                assertThat(tool.manager().availableAfterRestart(tool.installer().listInstalled().getFirst())).isTrue();
            }
        });
        try (var session = PluginBootstrapSession.createContext(plugins, enabled).start()) {
            var lifecycle = mock(PluginLifecycleService.class);
            var toggles = new PluginToggleProperties();
            var resolver = new PluginDependencyResolver(session.installer(),
                    lifecycle, session.manager(), toggles);
            var coordinator = new ExternalPluginLifecycleCoordinator(session.manager(), lifecycle, session.installer(),
                    mock(RecoveryModeService.class), resolver);
            var service = new PluginInstallService(coordinator, resolver);
            Path backend = PluginTestProvenance.stageRepairProbe(root, "backend", "1.0.0", "plugin.dependencies=" + id + "@2.0");
            var wrongVersion = service.installTrustedFile(backend, false, origin(backend));
            assertThat(wrongVersion.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_DEPENDENCY);
            assertThat(wrongVersion.dependencyProblems().getFirst().reason())
                    .isEqualTo(PluginDependencyProblem.Reason.VERSION_UNSATISFIED);

            backend = PluginTestProvenance.stageRepairProbe(root, "backend", "1.0.0", "pixiv.lifecycle-policy=" + policy + "\nplugin.dependencies=" + id + "@1.1");
            var descriptor = PluginPackageReader.inspect(backend).descriptor();
            assertThat(resolver.installedProblems(descriptor)).isEmpty();
            toggles.setEnabled(id, false);
            assertThat(service.installTrustedFile(backend, false, origin(backend)).dependencyProblems())
                    .singleElement().satisfies(problem -> assertThat(problem.status()).isEqualTo("DISABLED"));
            toggles.setEnabled(id, true);
            assertThat(resolver.activationProblems(descriptor)).singleElement()
                    .satisfies(problem -> assertThat(problem.availableAfterRestart()).isTrue());
            var dependency = session.installer().listInstalled().getFirst();
            session.installer().revokeTrust(id);
            assertThat(service.installTrustedFile(backend, false, origin(backend)).outcome())
                    .isEqualTo(PluginInstallOutcome.REJECTED_DEPENDENCY);
            session.installer().approveTrust(id, PluginPackageIntegrity.sha256Hex(dependency.path()));
            var installed = service.installTrustedFile(backend, false, origin(backend));
            assertThat(installed.outcome()).as("%s", installed).isEqualTo(PluginInstallOutcome.INSTALLED);
            assertThat(installed.activated()).isFalse();
            assertThat(installed.effectiveAfterRestart()).isTrue();
            var duplicate = service.installTrustedFile(backend, false, origin(backend));
            assertThat(duplicate.outcome()).isEqualTo(PluginInstallOutcome.DUPLICATE);
            assertThat(duplicate.effectiveAfterRestart()).isTrue();
            assertThat(session.manager().packagePhases()).isEmpty();
        }
        try (var restarted = PluginBootstrapSession.createContext(plugins, enabled).start()) {
            assertThat(restarted.status().failures()).isEmpty();
            assertThat(restarted.status().startedPluginIds()).containsExactlyInAnyOrder(id, "backend");
        }
    }

    @Test
    void startupFailureIsNotAHeadlessSkip() throws Exception {
        Path plugins = root.resolve("plugins");
        var broken = PluginTestProvenance.stageRepairProbe(plugins, "broken", "7.0.0", "pixiv.desktop-only=true");
        var existing = PluginTestProvenance.stageRepairProbe(plugins, "existing", "1.0.0", "plugin.dependencies=broken@7.0");
        for (var jar : List.of(broken, existing)) PluginTestProvenance.writeVerifiedLocalUpload(plugins, jar);
        try (var session = PluginBootstrapSession.createContext(plugins, PluginEnabledSnapshot.empty(),
                PluginTestProvenance.verifier()).start()) {
            assertThat(session.status().failures()).anyMatch(failure -> failure.source().equals("broken"));
            var lifecycle = mock(PluginLifecycleService.class);
            var resolver = new PluginDependencyResolver(session.installer(), lifecycle,
                    session.manager(), new PluginToggleProperties());
            var coordinator = new ExternalPluginLifecycleCoordinator(session.manager(), lifecycle, session.installer(),
                    mock(RecoveryModeService.class), resolver);
            var candidate = PluginTestProvenance.stageRepairProbe(root, "new-backend", "1.0.0",
                    "pixiv.lifecycle-policy=process-restart\nplugin.dependencies=broken@7.0");
            var result = coordinator.installOrUpdate(candidate, false, origin(candidate));
            assertThat(result.installResult().outcome()).isEqualTo(PluginInstallOutcome.REJECTED_DEPENDENCY);
            assertThat(session.installer().listInstalled()).noneMatch(plugin -> plugin.id().equals("new-backend"));
        }
    }

    @Test
    void rejectsDeferredUpgradeThatClosesCycleThroughRunningBackend() throws Exception {
        Path plugins = root.resolve("plugins");
        seed(plugins, "backend", "1.0.0", "");
        seed(plugins, "desktop", "1.0.0", "pixiv.desktop-only=true\nplugin.dependencies=backend@1.0");
        try (var session = start(plugins)) {
            assertThat(session.status().startedPluginIds()).containsExactly("backend");
            var services = new Services(session);
            var candidate = PluginTestProvenance.stageRepairProbe(root, "backend", "1.1.0", "plugin.dependencies=desktop@1.0");
            var rejected = services.install.installTrustedFile(candidate, false, origin(candidate));
            assertThat(rejected.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_DEPENDENCY);
            assertThat(rejected.dependencyProblems()).singleElement().satisfies(problem -> {
                assertThat(problem.reason()).isEqualTo(PluginDependencyProblem.Reason.CYCLE);
                assertThat(problem.status()).isEqualTo("backend -> desktop -> backend");
            });
            assertThat(services.entry("backend").version()).isEqualTo("1.0.0");
            assertThat(session.installer().listInstalled()).filteredOn(plugin -> plugin.id().equals("backend"))
                    .singleElement().satisfies(plugin -> assertThat(plugin.version()).isEqualTo("1.0.0"));
        }
        try (var restarted = start(plugins)) {
            assertThat(restarted.status().failures()).isEmpty();
            assertThat(restarted.status().startedPluginIds()).containsExactly("backend");
            assertThat(restarted.manager().loadedDescriptor("backend").orElseThrow().version()).isEqualTo("1.0.0");
        }
    }

    @Test
    void deferredUpgradeReportsInstalledAndRunningGenerationsUntilRestart() throws Exception {
        Path plugins = root.resolve("plugins");
        seed(plugins, "backend", "1.0.0", "");
        seed(plugins, "desktop", "1.0.0", "pixiv.desktop-only=true");
        try (var session = start(plugins)) {
            var services = new Services(session);
            var candidate = PluginTestProvenance.stageRepairProbe(root, "backend", "1.1.0", "plugin.dependencies=desktop@1.0");
            var upgraded = services.install.installTrustedFile(candidate, false, origin(candidate));
            assertThat(upgraded.outcome()).isEqualTo(PluginInstallOutcome.UPGRADED);
            assertThat(upgraded.effectiveAfterRestart()).isTrue();
            var entry = services.entry("backend");
            assertThat(entry.version()).isEqualTo("1.1.0");
            assertThat(entry.loadedVersion()).isEqualTo("1.0.0");
            assertThat(entry.effectiveAfterRestart()).isTrue();
            assertThat(entry.verification().status()).isEqualTo("UNSIGNED_ALLOWED");
            assertThat(entry.trust().artifactSha256()).isEqualTo(PluginPackageIntegrity.sha256Hex(candidate));
            assertThatThrownBy(() -> services.coordinator.reload("backend"))
                    .isInstanceOf(PluginLifecycleException.class).hasMessageContaining("desktop");
            assertThat(session.manager().loadedDescriptor("backend").orElseThrow().version()).isEqualTo("1.0.0");
            var dependent = PluginTestProvenance.stageRepairProbe(root, "consumer", "1.0.0", "plugin.dependencies=backend@1.1");
            assertThat(services.install.installTrustedFile(dependent, false, origin(dependent)).effectiveAfterRestart()).isTrue();
        }
        try (var restarted = start(plugins)) {
            assertThat(restarted.status().failures()).isEmpty();
            assertThat(restarted.status().startedPluginIds()).containsExactlyInAnyOrder("backend", "desktop", "consumer");
            assertThat(restarted.manager().loadedDescriptor("backend").orElseThrow().version()).isEqualTo("1.1.0");
            assertThat(new Services(restarted).entry("backend").effectiveAfterRestart()).isFalse();
        }
    }

    @Test
    void installsRequiredChainInOneSessionAndRechecksTransitiveTrustAndEnablement() throws Exception {
        Path plugins = root.resolve("plugins");
        seed(plugins, "desktop", "1.0.0", "pixiv.desktop-only=true");
        try (var session = start(plugins)) {
            var services = new Services(session);
            var mid = PluginTestProvenance.stageRepairProbe(root, "mid", "1.0.0", "plugin.dependencies=desktop@1.0");
            var last = PluginTestProvenance.stageRepairProbe(root, "last", "1.0.0", "plugin.dependencies=mid@1.0");
            var first = services.install.installTrustedFile(mid, false, origin(mid));
            assertThat(first.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
            assertThat(first.effectiveAfterRestart()).isTrue();
            assertThat(services.entry("mid").effectiveAfterRestart()).isTrue();
            services.toggles.setEnabled("desktop", false);
            assertThat(services.install.installTrustedFile(last, false, origin(last)).dependencyProblems())
                    .singleElement().satisfies(problem -> assertThat(problem.status()).isEqualTo("DISABLED"));
            services.toggles.setEnabled("desktop", true);
            session.installer().revokeTrust("mid");
            assertThat(services.install.installTrustedFile(last, false, origin(last)).outcome())
                    .isEqualTo(PluginInstallOutcome.REJECTED_DEPENDENCY);
            session.installer().approveTrust("mid", PluginPackageIntegrity.sha256Hex(mid));
            var second = services.install.installTrustedFile(last, false, origin(last));
            assertThat(second.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
            assertThat(second.effectiveAfterRestart()).isTrue();
            var cycle = PluginTestProvenance.stageRepairProbe(root, "mid", "1.1.0", "plugin.dependencies=last@1.0");
            var rejected = services.install.installTrustedFile(cycle, false, origin(cycle));
            assertThat(rejected.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_DEPENDENCY);
            assertThat(rejected.dependencyProblems()).singleElement().satisfies(problem ->
                    assertThat(problem.reason()).isEqualTo(PluginDependencyProblem.Reason.CYCLE));
            assertThat(services.entry("mid").version()).isEqualTo("1.0.0");
            assertThat(services.entry("mid").effectiveAfterRestart()).isTrue();
            assertThat(session.manager().packagePhases()).isEmpty();
        }
        try (var restarted = start(plugins)) {
            assertThat(restarted.status().failures()).isEmpty();
            assertThat(restarted.status().startedPluginIds()).containsExactlyInAnyOrder("desktop", "mid", "last");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,2.0.0,false", "true,2.0.0,false",
            "false,1.1.0,true", "true,1.1.0,true"})
    void deferredUpgradePreservesRequiredDependents(boolean pending, String version, boolean compatible) throws Exception {
        Path plugins = root.resolve("plugins");
        seed(plugins, "desktop", "1.0.0", "pixiv.desktop-only=true");
        if (!pending) {
            seed(plugins, "mid", "1.0.0", "");
            seed(plugins, "last", "1.0.0", "plugin.dependencies=mid@1.0");
        }
        String expectedVersion = compatible ? version : "1.0.0";
        try (var session = start(plugins)) {
            var services = new Services(session);
            if (pending) {
                var mid = PluginTestProvenance.stageRepairProbe(root, "mid", "1.0.0", "plugin.dependencies=desktop@1.0");
                var last = PluginTestProvenance.stageRepairProbe(root, "last", "1.0.0", "plugin.dependencies=mid@1.0");
                assertThat(services.install.installTrustedFile(mid, false, origin(mid)).effectiveAfterRestart()).isTrue();
                assertThat(services.install.installTrustedFile(last, false, origin(last)).effectiveAfterRestart()).isTrue();
            } else {
                assertThat(session.manager().activeDependents("mid")).contains("last");
            }
            String oldDigest = services.entry("mid").trust().artifactSha256();
            var candidate = PluginTestProvenance.stageRepairProbe(root, "mid", version, "plugin.dependencies=desktop@1.0");
            var result = services.install.installTrustedFile(candidate, false, origin(candidate));
            assertThat(result.outcome()).isEqualTo(compatible ? PluginInstallOutcome.UPGRADED : PluginInstallOutcome.REJECTED_DEPENDENCY);
            assertThat(result.effectiveAfterRestart()).isEqualTo(compatible);
            if (!compatible) {
                assertThat(result.dependencyProblems()).singleElement().satisfies(problem -> {
                    assertThat(problem.reason()).isEqualTo(PluginDependencyProblem.Reason.DEPENDENT_CONFLICT);
                    assertThat(problem.pluginId()).isEqualTo("mid");
                    assertThat(problem.status()).isEqualTo("last");
                    assertThat(problem.installedVersion()).isEqualTo("1.0.0");
                });
            }
            var disk = services.entry("mid");
            assertThat(disk.version()).isEqualTo(expectedVersion);
            assertThat(disk.trust().artifactSha256()).isEqualTo(compatible ? PluginPackageIntegrity.sha256Hex(candidate) : oldDigest);
        }
        try (var restarted = start(plugins)) {
            assertThat(restarted.status().failures()).isEmpty();
            assertThat(restarted.status().startedPluginIds()).contains("mid", "last");
            assertThat(restarted.manager().loadedDescriptor("mid").orElseThrow().version()).isEqualTo(expectedVersion);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,process-restart", "true,process-restart",
            "false,hot-reload", "true,hot-reload"})
    void newlyCommittedDesktopCanSatisfyConsumerBeforeFirstRestart(boolean upgrade, String policy) throws Exception {
        Path plugins = root.resolve("plugins");
        String properties = "pixiv.desktop-only=true\npixiv.lifecycle-policy=" + policy;
        if (upgrade) seed(plugins, "desktop", "1.0.0", properties);
        var desktop = PluginTestProvenance.stageRepairProbe(root, "desktop", "1.1.1", properties);
        var consumer = PluginTestProvenance.stageRepairProbe(root, "consumer", "1.0.0", "plugin.dependencies=desktop@1.1");
        try (var session = start(plugins)) {
            var services = new Services(session);
            var installed = services.install.installTrustedFile(desktop, false, origin(desktop));
            assertThat(installed.outcome()).isEqualTo(upgrade ? PluginInstallOutcome.UPGRADED : PluginInstallOutcome.INSTALLED);
            assertThat(installed.activated()).isFalse();
            assertThat(installed.effectiveAfterRestart()).isFalse();
            var entry = services.entry("desktop");
            assertThat(entry.runtimePhase()).isNull();
            assertThat(entry.effectiveAfterRestart()).isFalse();
            assertThat(entry.trust().artifactSha256()).isEqualTo(PluginPackageIntegrity.sha256Hex(desktop));
            assertThat(services.install.installTrustedFile(desktop, false, origin(desktop)).effectiveAfterRestart()).isFalse();

            var incompatible = PluginTestProvenance.stageRepairProbe(root, "wrong", "1.0.0", "plugin.dependencies=desktop@2.0");
            assertThat(services.install.installTrustedFile(incompatible, false, origin(incompatible)).dependencyProblems())
                    .singleElement().satisfies(problem -> assertThat(problem.reason()).isEqualTo(PluginDependencyProblem.Reason.VERSION_UNSATISFIED));
            services.toggles.setEnabled("desktop", false);
            assertThat(services.install.installTrustedFile(consumer, false, origin(consumer)).dependencyProblems())
                    .singleElement().satisfies(problem -> assertThat(problem.status()).isEqualTo("DISABLED"));
            services.toggles.setEnabled("desktop", true);
            session.installer().revokeTrust("desktop");
            assertThat(services.install.installTrustedFile(consumer, false, origin(consumer)).outcome())
                    .isEqualTo(PluginInstallOutcome.REJECTED_DEPENDENCY);
            session.installer().approveTrust("desktop", PluginPackageIntegrity.sha256Hex(desktop));
            var result = services.install.installTrustedFile(consumer, false, origin(consumer));
            assertThat(result.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
            assertThat(result.effectiveAfterRestart()).isTrue();
            assertThat(services.entry("desktop").effectiveAfterRestart()).isTrue();
            assertThat(session.manager().packagePhases()).isEmpty();
            services.coordinator.remove("consumer");
            assertThat(services.entry("desktop").effectiveAfterRestart()).isFalse();
            assertThat(services.install.installTrustedFile(desktop, false, origin(desktop)).effectiveAfterRestart()).isFalse();
            assertThat(services.install.installTrustedFile(consumer, false, origin(consumer)).effectiveAfterRestart()).isTrue();
        }
        try (var restarted = start(plugins)) {
            assertThat(restarted.status().failures()).isEmpty();
            assertThat(restarted.status().startedPluginIds()).containsExactlyInAnyOrder("desktop", "consumer");
            assertThat(new Services(restarted).entry("desktop").effectiveAfterRestart()).isFalse();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void restartDependenciesUseCurrentAdmissionOfTheDiskArtifact(boolean indirect, boolean upgrade) throws Exception {
        Path plugins = root.resolve("plugins");
        String policy = "pixiv.lifecycle-policy=process-restart";
        seed(plugins, "provider", "1.0.0", policy);
        if (indirect) seed(plugins, "middle", "1.0.0", "plugin.dependencies=provider@1.0");
        var consumer = PluginTestProvenance.stageRepairProbe(root, "consumer", "1.0.0",
                policy + "\nplugin.dependencies=" + (indirect ? "middle" : "provider") + "@1.0");
        try (var session = start(plugins)) {
            var services = new Services(session);
            if (upgrade) {
                var next = PluginTestProvenance.stageRepairProbe(root, "provider", "1.0.1", policy);
                assertThat(services.install.installTrustedFile(next, false, origin(next)).effectiveAfterRestart()).isTrue();
            }
            String sha = services.entry("provider").trust().artifactSha256();
            services.management.revokeTrust("provider");
            assertThat(session.manager().packagePhases().get("provider"))
                    .isEqualTo(PluginRuntimePackagePhase.STARTED);
            var denied = services.install.installTrustedFile(consumer, false, origin(consumer));
            assertThat(denied.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_DEPENDENCY);
            assertThat(session.installer().listInstalled()).noneMatch(item -> item.id().equals("consumer"));
            assertThat(denied.dependencyProblems()).singleElement().satisfies(problem -> {
                assertThat(problem.pluginId()).isEqualTo("provider");
                assertThat(problem.availableAfterRestart()).isFalse();
                if (!upgrade) assertThat(problem.status()).isEqualTo("ADMISSION_REJECTED");
            });
            var direct = PluginTestProvenance.stageRepairProbe(root, "live-check", "1.0.0", "plugin.dependencies=provider@1.0");
            assertThat(services.resolver.activationProblems(PluginPackageReader.inspect(direct).descriptor())).isEmpty();
            session.installer().approveTrust("provider", sha);
            assertThat(services.install.installTrustedFile(consumer, false, origin(consumer)).effectiveAfterRestart()).isTrue();
        }
        try (var session = start(plugins)) {
            assertThat(session.status().failures()).isEmpty();
            assertThat(session.status().startedPluginIds()).contains("consumer", "provider");
            assertThat(session.manager().loadedDescriptor("provider").orElseThrow().version())
                    .isEqualTo(upgrade ? "1.0.1" : "1.0.0");
        }
    }

    private static PluginBootstrapSession start(Path plugins) {
        return PluginBootstrapSession.createContext(plugins, PluginEnabledSnapshot.empty(), PluginTestProvenance.verifier()).start();
    }

    private static void seed(Path plugins, String id, String version, String properties) throws Exception {
        var artifact = PluginTestProvenance.stageRepairProbe(plugins.resolveSibling("seed"), id, version, properties);
        try (var installer = new ExternalPluginInstaller(plugins)) {
            installer.recoverPendingTransactions();
            var prepared = installer.prepareTransaction(artifact, false, origin(artifact));
            assertThat(prepared.readyToCommit()).isTrue();
            var committed = installer.commitTransaction(prepared);
            installer.verifyCommittedTarget(committed);
            installer.markActivated(committed);
            installer.completeTransaction(committed);
        }
    }

    private static final class Services {
        final PluginToggleProperties toggles = new PluginToggleProperties();
        final ExternalPluginLifecycleCoordinator coordinator;
        final PluginInstallService install;
        final PluginDependencyResolver resolver;
        final PluginManagementService management;

        Services(PluginBootstrapSession session) {
            var runtime = session.manager();
            var installer = session.installer();
            var registry = mock(PluginRegistry.class);
            var lifecycle = mock(PluginLifecycleService.class);
            when(lifecycle.managedPluginIds()).thenAnswer(call -> runtime.loadedDescriptors().keySet());
            when(lifecycle.descriptor(anyString())).thenAnswer(call -> runtime.loadedDescriptor(call.getArgument(0)));
            when(lifecycle.artifactPath(anyString())).thenAnswer(call -> runtime.artifactPath(call.getArgument(0)));
            when(lifecycle.phase(anyString())).thenAnswer(call -> session.status().startedPluginIds().contains(call.getArgument(0))
                    ? Optional.of(PluginRuntimePhase.STARTED) : Optional.empty());
            var recovery = mock(RecoveryModeService.class);
            resolver = new PluginDependencyResolver(installer, lifecycle, runtime, toggles);
            coordinator = new ExternalPluginLifecycleCoordinator(runtime, lifecycle, installer, recovery, resolver);
            install = new PluginInstallService(coordinator, resolver);
            var required = RequiredPluginPolicy.empty();
            management = new PluginManagementService(new PluginStatusService(registry, runtime, installer, required, toggles),
                    lifecycle, required, recovery, coordinator, installer, toggles);
        }

        PluginManagementService.PluginManagementEntry entry(String id) {
            return management.list().plugins().stream().filter(entry -> id.equals(entry.id())).findFirst().orElseThrow();
        }
    }

    private static PluginPackageOrigin origin(Path artifact) throws Exception {
        return PluginPackageOrigin.localUnsignedUpload(PluginPackageIntegrity.sha256Hex(artifact));
    }
}
