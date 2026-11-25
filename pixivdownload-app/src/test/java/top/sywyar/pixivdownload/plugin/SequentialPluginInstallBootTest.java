package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.PixivDownloadApplication;
import top.sywyar.pixivdownload.gui.BackendLifecycleManager;
import top.sywyar.pixivdownload.transfer.EnvironmentExportService;
import top.sywyar.pixivdownload.transfer.EnvironmentTransferService;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRegistrar;
import java.io.BufferedWriter;
import java.io.IOException;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.core.push.PushChannelRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityUnavailableException;
import top.sywyar.pixivdownload.push.PushChannelId;
import top.sywyar.pixivdownload.push.RenderedMessage;
import org.springframework.test.util.ReflectionTestUtils;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogService;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogEntry;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.catalog.page.PluginCatalogDetailPage;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.management.PluginEnabledConfigurationService;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.market.PluginMarketService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.repairprobe.StartupRepairProbePlugin;
import top.sywyar.pixivdownload.setup.SetupService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class SequentialPluginInstallBootTest {
    private static final String DESKTOP = "pixiv.desktop-only=true";
    private static final String RESTART = "pixiv.lifecycle-policy=process-restart";

    @TempDir Path root;

    @AfterEach
    void removePrivatePreparationCache() {
        assertThat(PluginArtifactSnapshot.deleteWorkspaceQuietly(root.resolve("state/plugin-preparation-cache"))).isTrue();
    }

    @Test
    void sequentialUploadsPreserveServingAndRestartGraphs() throws Exception {
        stageBootPackages();
        runProcesses("install", "restart");
    }

    @Test
    void managementActionsRespectResidentAndPendingGenerations() throws Exception {
        stageBootPackages();
        runProcesses("management", "management-restart");
    }

    @Test
    void processRestartConsumersRetainTheirReplacedProviders() throws Exception {
        stageBootPackages();
        runProcesses("replacement", "replacement-restart");
    }

    @Test
    void requiredServiceGraphSurvivesRollbackAndColdBootFailures() throws Exception {
        stageBootPackages();
        runProcesses("graph", "graph-broken-restart", "graph-restart");
    }

    @Test
    void deferredInitializationFailureFollowsTheArtifactAcrossRetries() throws Exception {
        stageBootPackages();
        runProcesses("init", "init-load-restart", "init-healthy-restart", "init-broken-restart", "init-restart");
    }

    @Test
    void quiescedReplacementNeverAcceptsPush() throws Exception {
        stageBootPackages();
        runProcesses("push");
    }

    @Test
    void requiredServiceKeepsRecoveryGateUntilServing() throws Exception {
        stageBootPackages();
        String gallery = System.getProperty("gallery.plugin.classes");
        if (gallery != null && Files.isRegularFile(Path.of(gallery, "plugin.properties"))) {
            PluginTestArtifacts.zipDirectoryAsJar(Path.of(gallery), root.resolve("input/gallery.jar"));
        }
        runProcesses("sentinel");
        Path middle;
        try (var installer = new ExternalPluginInstaller(root.resolve("plugins"))) {
            installer.recoverPendingTransactions();
            middle = installer.listInstalled().stream().filter(p -> p.id().equals("sentinel-middle")).findFirst().orElseThrow().path();
        }
        var held = Files.move(middle, root.resolve("held-middle.jar"));
        try { runProcesses("sentinel-missing-restart"); }
        finally { Files.move(held, middle); }
        runProcesses("sentinel-restart");
    }

    @Test
    void headlessDemandFollowsEnabledAndTrustedConsumersAcrossProcesses() throws Exception {
        stageBootPackages();
        runProcesses("demand", "demand-restart");
    }

    @Test
    void disabledHotPackagesAndRestartHintsThroughManagementPage() throws Exception {
        stageBootPackages();
        var input = root.resolve("input");
        stageApproved(List.of(probe(input, "cycle-backend", "1.0.0", requires("cycle-ui")),
                probe(input, "cycle-ui", "1.0.0", DESKTOP + "\n" + requires("cycle-backend?")),
                probe(input, "required-cycle-a", "1.0.0", requires("required-cycle-b")),
                probe(input, "required-cycle-b", "1.0.0", requires("required-cycle-a"))));
        runProcesses("toggles", "toggles-restart", "toggles-again-restart");
    }

    @Test
    void coldBootAndProjectionAgreeOnDuplicateAdmission() throws Exception {
        stageBootPackages();
        runProcesses("duplicates", "duplicates-restart");
    }

    @Test
    void headlessExportRetriesWithTheLiveWriter() throws Exception {
        stageBootPackages();
        runProcesses("export");
    }

    @Test
    void shutdownWritesThroughLiveContextsInDependencyOrder() throws Exception {
        stageBootPackages();
        for (String phase : List.of("shutdown", "shutdown-restart")) {
            runProcesses(phase);
            var records = Files.readAllLines(root.resolve("stop.log"));
            assertThat(records).containsExactly("writer-hot", "writer-leaf", "writer-mid", "writer-root");
            try (var lines = Files.lines(root.resolve(phase + ".log"))) {
                String prefix = "writer stop entered ";
                assertThat(lines.filter(line -> line.startsWith(prefix)).map(line -> line.substring(prefix.length())))
                        .containsExactlyElementsOf(records);
            }
            Files.move(root.resolve("stop.log"), root.resolve(phase + "-stop.log"));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"hook-normal", "hook-once", "hook-persistent"})
    void headlessShutdownClosesDrainedWriters(String phase) throws Exception {
        stageBootPackages();
        runProcesses(phase);
        assertThat(Files.readAllLines(root.resolve("stop.log")))
                .containsExactly("writer-hot", "writer-leaf", "writer-mid", "writer-root");
        var order = new java.util.ArrayList<>(List.of("writer-hot", "writer-leaf", "writer-mid", "writer-root"));
        if (!phase.equals("hook-normal")) order.add(2, "writer-leaf");
        try (var lines = Files.lines(root.resolve(phase + ".log"))) {
            String prefix = "writer stop entered ";
            assertThat(lines.filter(line -> line.startsWith(prefix)).map(line -> line.substring(prefix.length())))
                    .containsExactlyElementsOf(order);
        }
    }

    @Test
    void desktopRestartWaitsForFailedPluginToStop() throws Exception {
        stageBootPackages();
        runProcesses("shutdown", "desktop-restart");
    }

    private void stageBootPackages() throws Exception {
        var input = Files.createDirectories(root.resolve("input"));
        PluginTestProvenance.stageSqliteProvider(input);
        stageApproved(List.of(input.resolve("database-sqlite-plugin.jar"),
                    probe(input, "updates", "1.0.0", ""), probe(input, "backend", "1.0.0", ""),
                    probe(input, "bridge", "1.0.0", requires("backend@1.0")),
                    probe(input, "desktop", "1.0.0", DESKTOP + "\n" + requires("updates@1.0")),
                    probe(input, "rollback", "1.0.0", ""), probe(input, "healthy", "1.0.0", ""),
                    probe(input, "provider", "1.0.0", RESTART)));
    }

    private void stageApproved(List<Path> artifacts) throws Exception {
        try (var installer = new ExternalPluginInstaller(root.resolve("plugins"))) {
            installer.recoverPendingTransactions();
            for (var artifact : artifacts) {
                var prepared = installer.prepareTransaction(artifact, false,
                        PluginPackageOrigin.localUnsignedUpload(PluginPackageIntegrity.sha256Hex(artifact)));
                assertThat(prepared.readyToCommit()).isTrue();
                var committed = installer.commitTransaction(prepared);
                installer.verifyCommittedTarget(committed);
                installer.markActivated(committed);
                installer.completeTransaction(committed);
            }
        }
    }

    private void runProcesses(String... phases) throws Exception {
        for (String phase : phases) {
            var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            var args = root.resolve("java.args");
            Files.writeString(args, "-cp\n\"" + classpath.replace('\\', '/') + "\"\n" + getClass().getName()
                    + "\n" + phase, StandardCharsets.UTF_8);
            var log = root.resolve(phase + ".log");
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "@" + args).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            if (phase.startsWith("toggles")) child.environment().put("PLUGIN_MANAGE_PAGE_TEST",
                    Path.of("src/test/js/plugin-manage-lifecycle.test.js").toAbsolutePath().toString());
            var process = child.start();
            try {
                assertThat(process.waitFor(3, TimeUnit.MINUTES)).as(phase).isTrue();
                assertThat(process.exitValue()).withFailMessage(() -> readLog(log)).isZero();
                if (phase.equals("graph-broken-restart")) {
                    try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
                        assertThat(lines.filter(line -> line.contains("DesktopUiDialogs") && line.contains("graph-")))
                                .singleElement().asString().contains("graph-base").doesNotContain("graph-mid", "graph-leaf");
                    }
                }
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
            }
        }
    }

    private static String readLog(Path log) {
        try { return Files.readString(log); }
        catch (java.io.IOException failure) { return failure.toString(); }
    }

    private static String requires(String edge) { return "plugin.dependencies=" + edge; }

    private static Path probe(Path input, String id, String version, String properties) throws Exception {
        return PluginTestProvenance.stageRepairProbe(input, id, version, properties);
    }

    public static void main(String[] args) throws Exception {
        Utf8ConsoleStreams.install();
        for (var dir : List.of("config", "state", "data", "plugins", "instance")) {
            System.setProperty("pixivdownload." + dir + "-dir", Files.createDirectories(Path.of(dir)).toAbsolutePath().toString());
        }
        System.setProperty("pixivdownload.headless", "true");
        System.setProperty("pixivdownload.plugin-dev.enabled", "false");
        boolean restart = args[0].endsWith("restart");
        String[] options = {"--server.address=127.0.0.1", "--server.port=0", "--update.enabled=false",
                "--setup.browser.auto-open=false", "--maintenance.enabled=false", "--proxy.enabled=false", "--update.auto-check=false"};
        if (args[0].equals("desktop-restart")) {
            checkDesktopRestart(options);
            return;
        }
        if (args[0].startsWith("sentinel")) System.setProperty("pixivdownload.recovery-sentinel.required", "true");
        if (args[0].equals("sentinel-restart")) {
            System.setProperty("test.plugin.fail-context", "true");
        }
        if (args[0].equals("replacement")) stageReplacementChains();
        if (args[0].equals("graph-broken-restart")) System.setProperty("test.plugin.fail-context", "true");
        if (args[0].equals("init-broken-restart")) System.setProperty("test.plugin.fail-init", "init-signed@1.0.0");
        try (var session = (restart && !args[0].startsWith("toggles") && !args[0].equals("demand-restart")) || args[0].equals("replacement") ? restartWithPublisherKey() : null;
             var app = session == null ? PixivDownloadApplication.start(options) : PixivDownloadApplication.start(options, session);
             var http = HttpClient.newHttpClient()) {
            var runtime = app.getBean(PluginRuntimeManager.class);
            if (args[0].equals("restart")) {
                assertThat(runtime.status().orElseThrow().failures()).singleElement()
                        .satisfies(failure -> assertThat(failure.source()).isEqualTo("reload-bad"));
                assertThat(runtime.status().orElseThrow().startedPluginIds()).doesNotContain("needs-core", "replacement", "reload-bad");
                assertThat(app.getBean(ExternalPluginInstaller.class).listInstalled())
                        .noneMatch(p -> p.id().equals("needs-core") || p.id().equals("replacement"));
                for (String id : List.of("backend", "bridge", "desktop", "updates", "healthy", "rollback",
                        "provider", "desktop-consumer", "both-consumer", "consumer-root", "original", "reload-good", "desktop-control",
                        "cold-retry", "cold-new")) {
                    assertThat(runtime.status().orElseThrow().startedPluginIds()).contains(id);
                    assertThat(entry(app, id).effectiveAfterRestart()).as(id).isFalse();
                }
                for (String id : List.of("backend", "rollback", "healthy", "original", "reload-good"))
                    assertThat(runtime.loadedDescriptor(id).orElseThrow().version()).isEqualTo("1.1.0");
                assertThat(runtime.loadedDescriptor("updates").orElseThrow().version()).isEqualTo("1.0.0");
                assertThat(runtime.loadedDescriptor("provider").orElseThrow().version()).isEqualTo("2.0.0");
                assertThat(runtime.status().orElseThrow().startedPluginIds())
                        .contains("retry-desktop", "retry-client", "bad-desktop", "repaired-client");
                assertThat(runtime.loadedDescriptor("bad-desktop").orElseThrow().version()).isEqualTo("8.0.0");
                assertThat(app.getBean(ExternalPluginInstaller.class).listInstalled()).noneMatch(p -> p.id().startsWith("rejected-client-"));
                return;
            }
            var setup = app.getBean(SetupService.class);
            if (!restart) setup.init("test-admin", UUID.randomUUID().toString(), "solo");
            var uploads = new Uploads(http, ((WebServerApplicationContext) app).getWebServer().getPort(), setup.createSession(true));
            if (args[0].startsWith("toggles")) {
                checkDisabledManagement(app, uploads, args[0]);
                return;
            }
            if (args[0].startsWith("duplicates")) {
                checkDuplicateAdmission(app, uploads, restart);
                return;
            }
            if (args[0].startsWith("demand")) {
                checkHeadlessDemand(app, uploads, restart);
                return;
            }
            if (args[0].equals("export")) {
                checkExportRetry(app, uploads, options);
                return;
            }
            if (args[0].equals("push")) {
                checkQuiescedPush(app, uploads);
                return;
            }
            if (args[0].startsWith("sentinel")) {
                if (args[0].equals("sentinel-missing-restart")) {
                    checkRecoveryMessages(uploads, "MISSING_REQUIRED", "plugin.recovery.missing-dependency");
                } else checkRecoveryGate(app, uploads, restart);
                return;
            }
            if (args[0].startsWith("shutdown") || args[0].startsWith("hook-")) {
                if (!restart) {
                    trustTestPublisher(app);
                    String writer = "pixiv.configuration-classes=" + StartupRepairProbePlugin.ShutdownConfig.class.getName();
                    uploads.signed("writer-root", "1.0.0", writer, 200);
                    uploads.signed("writer-mid", "1.0.0", writer + "\n" + requires("writer-root"), 200);
                    uploads.signed("writer-leaf", "1.0.0", writer + "\n" + requires("writer-mid"), 200);
                    uploads.signed("writer-hot", "1.0.0", writer, 200);
                }
                if (args[0].startsWith("hook-")) {
                    var lifecycle = app.getBean(PluginLifecycleService.class);
                    for (String id : List.of("writer-root", "writer-mid", "writer-leaf", "writer-hot")) {
                        lifecycle.contextFor(id).orElseThrow().getBean(BufferedWriter.class).write(id + "\n");
                    }
                    assertThat(Files.size(Path.of("stop.log"))).isZero();
                    System.setProperty("test.stop.buffered-only", "true");
                    if (!args[0].equals("hook-normal"))
                        System.setProperty("test.stop.failures.writer-leaf", args[0].equals("hook-once") ? "1" : "-1");
                    System.exit(0); // 触发无图形 Spring JVM 的关闭钩子，由父进程核对已写入的文件。
                }
                uploads.action("writer-hot", "stop", 200);
                assertThat(Files.readAllLines(Path.of("stop.log"))).containsExactly("writer-hot");
                return;
            }
            if (args[0].startsWith("graph")) {
                checkServiceGraph(args[0], app, uploads);
                return;
            }
            if (args[0].startsWith("init")) {
                if (restart) app.getBean(PluginBootstrapSession.class).updateVerifier(publisherVerifier());
                checkInitializationFailure(args[0], app, uploads);
                return;
            }
            if (args[0].startsWith("replacement")) {
                checkReplacementChains(app, uploads, restart);
                return;
            }
            if (args[0].equals("management-restart")) {
                assertThat(runtime.status().orElseThrow().failures()).isEmpty();
                for (String id : List.of("library-client", "idle-original", "load-desktop", "before-load", "after-load", "load-only-hot")) {
                    assertThat(entry(app, id).runtimePhase().name()).as(id).isEqualTo("STARTED");
                    assertThat(entry(app, id).effectiveAfterRestart()).as(id).isFalse();
                }
                assertThat(runtime.loadedDescriptor("library-client").orElseThrow().version()).isEqualTo("1.1.0");
                assertThat(runtime.loadedDescriptor("idle-original").orElseThrow().version()).isEqualTo("1.1.0");
                assertThat(uploads.impact("library").path("consumers").size()).isZero();
                uploads.action("library", "remove", 200);
                return;
            }
            if (args[0].equals("management")) {
                trustTestPublisher(app);
                checkRestartPolicyAndImpact(app, runtime, uploads);
                preserveAdminPhaseOnRollback(app, uploads);
                replaceInDependencyOrder(app, uploads);
                acceptLoadedPendingProvider(app, uploads);
                reloadWithChangedDependencies(uploads);
                return;
            }
            rejectHotUpdateThatClosesDiskCycle(app, runtime, uploads);
            restoreServingVersionAfterFailedUpdate(app, runtime, uploads);
            checkProcessRestartProviderVersions(app, uploads);
            checkReplacementAndReload(app, uploads);
        }
    }

    private static void checkHeadlessDemand(ConfigurableApplicationContext app, Uploads uploads, boolean restart) throws Exception {
        for (String scenario : List.of("disabled", "untrusted", "shared")) {
            String provider = "ui-" + scenario;
            String consumer = "consumer-" + scenario;
            boolean needed = scenario.equals("shared");
            if (!restart) {
                uploads.stored(provider, "1.0.0", DESKTOP, false);
                uploads.deferred(consumer, "1.0.0", requires(provider));
                if (needed) uploads.deferred("second-consumer", "1.0.0", requires(provider));
                if (scenario.equals("disabled")) {
                    app.getBean(PluginEnabledConfigurationService.class).update(consumer, false);
                } else {
                    app.getBean(PluginManagementService.class).revokeTrust(consumer);
                }
                for (String id : List.of(provider, consumer)) {
                    boolean pending = id.equals(provider) && needed;
                    assertThat(uploads.impact(id).at("/plugin/effectiveAfterRestart").asBoolean()).as(id).isEqualTo(pending);
                    var artifact = app.getBean(ExternalPluginInstaller.class).listInstalled().stream()
                            .filter(item -> item.id().equals(id)).findFirst().orElseThrow().path();
                    boolean revoked = id.equals(consumer) && !scenario.equals("disabled");
                    var receipt = uploads.upload(artifact, null, revoked ? 409 : 200, !revoked);
                    assertThat(receipt.path("effectiveAfterRestart").asBoolean()).as(id).isEqualTo(pending);
                    assertThat(receipt.path("outcome").asString()).isEqualTo(revoked ? "TRUST_CONFIRMATION_REQUIRED" : "DUPLICATE");
                    assertMarketRestart(app, id, artifact, pending);
                    if (revoked) {
                        assertThat(uploads.upload(artifact, null, 200).path("effectiveAfterRestart").asBoolean()).isTrue();
                        app.getBean(PluginManagementService.class).revokeTrust(id);
                    }
                }
            } else {
                assertThat(entry(app, provider).configuredEnabled()).isTrue();
                assertThat(entry(app, provider).runtimePhase() != null).isEqualTo(needed);
                if (needed) {
                    assertThat(uploads.probeVersion(provider, 200)).isEqualTo("1.0.0");
                    assertThat(uploads.probeVersion("second-consumer", 200)).isEqualTo("1.0.0");
                } else uploads.probeVersion(provider, 404);
                assertThat(entry(app, consumer).runtimePhase()).isNull();
                if (scenario.equals("disabled")) assertThat(entry(app, consumer).status().name()).isEqualTo("DISABLED");
                else assertThat(app.getBean(PluginRuntimeManager.class).status().orElseThrow().failures())
                        .anyMatch(f -> f.source().contains(consumer) && f.reason().contains("revoked"));
                assertThat(entry(app, provider).effectiveAfterRestart()).isFalse();
            }
        }
    }

    private static void checkDisabledManagement(ConfigurableApplicationContext app, Uploads uploads, String phase) throws Exception {
        String desktop = "standalone-ui";
        if (phase.equals("toggles")) {
            var failures = app.getBean(PluginRuntimeManager.class).status().orElseThrow().failures();
            for (String id : List.of("cycle-backend", "cycle-ui", "required-cycle-a", "required-cycle-b")) {
                assertThat(failures).anyMatch(f -> f.source().contains(id) && f.reason().contains("cyclic"));
                uploads.probeVersion(id, 404);
            }
            uploads.probeVersion("healthy", 200);
            uploads.stored(desktop, "1.0.0", DESKTOP + "\n" + RESTART, false);
            for (String id : List.of("cancel-enable", "fail-load", "fail-start", "backend-remove")) {
                uploads.post(id, "1.0.0", id.equals("backend-remove") ? "pixiv.lifecycle-policy=backend-restart" : "", 200);
            }
            uploads.post("waiting-middle", "1.0.0", requires("provider"), 200);
            uploads.post("waiting-leaf", "1.0.0", requires("waiting-middle"), 200);
            uploads.deferred("provider", "2.0.0", RESTART);
            for (String id : List.of("healthy", "rollback", "provider", "backend-remove", "cancel-enable", "fail-load", "fail-start"))
                uploads.writeJson("PUT", "/api/plugins/" + id + "/enabled", java.util.Map.of("enabled", false), 200);
            var updated = app.getBean(ExternalPluginInstaller.class).listInstalled().stream()
                    .filter(item -> item.id().equals("provider")).findFirst().orElseThrow();
            assertThat(entry(app, "provider").loadedVersion()).isEqualTo("1.0.0");
            assertThat(uploads.probeVersion("provider", 200)).isEqualTo("1.0.0");
            assertThat(entry(app, "provider").effectiveAfterRestart()).isTrue();
            assertThat(uploads.upload(updated.path(), null, 200).path("effectiveAfterRestart").asBoolean()).isTrue();
            assertMarketRestart(app, "provider", updated.path(), true);
            checkManagementPage(uploads, phase);
            return;
        }
        var card = uploads.impact(desktop).path("plugin");
        assertThat(card.path("status").asString()).isEqualTo("INSTALLED");
        assertThat(card.path("runtimePhase").isNull()).isTrue();
        assertThat(card.path("effectiveAfterRestart").asBoolean()).isFalse();
        if (phase.equals("toggles-restart")) {
            assertThat(entry(app, "rollback").status().name()).isEqualTo("DISABLED");
            assertThat(entry(app, "rollback").availableActions()).containsExactly("remove");
            uploads.action("rollback", "remove", 200);
            assertThat(app.getBean(ExternalPluginInstaller.class).listInstalled()).noneMatch(p -> p.id().equals("rollback"));
            assertThat(entry(app, "backend-remove").availableActions()).containsExactly("remove");
            assertThat(uploads.action("backend-remove", "remove", 200).path("effectiveAfterRestart").asBoolean()).isFalse();
            uploads.action("healthy", "remove", 200);
            uploads.stored("healthy", "1.1.0", "", false);
            var restored = entry(app, "healthy");
            assertThat(restored.configuredEnabled()).isFalse();
            assertThat(restored.runtimePhase()).isNull();
            assertThat(restored.version()).isEqualTo("1.1.0");
            uploads.probeVersion("healthy", 404);
            for (String id : List.of("waiting-middle", "waiting-leaf")) assertThat(entry(app, id).effectiveAfterRestart()).isFalse();
            uploads.writeJson("PUT", "/api/plugins/provider/enabled", java.util.Map.of("enabled", true), 200);
            for (String id : List.of("provider", "waiting-middle", "waiting-leaf")) {
                var waiting = entry(app, id);
                assertThat(waiting.effectiveAfterRestart()).as(id).isTrue();
                assertThat(waiting.loadedVersion()).as(id).isNull();
            }
            System.setProperty("test.plugin.fail-init", "fail-load@1.0.0");
            System.setProperty("test.plugin.fail-start", "fail-start@1.0.0");
        } else {
            for (String id : List.of("waiting-middle", "waiting-leaf")) {
                uploads.probeVersion(id, 200);
                assertThat(entry(app, id).effectiveAfterRestart()).isFalse();
            }
            assertThat(uploads.probeVersion("provider", 200)).isEqualTo("2.0.0");
        }
        checkManagementPage(uploads, phase);
        assertThat(uploads.probeVersion("healthy", 200)).isEqualTo("1.1.0");
        for (String id : List.of("cancel-enable", "fail-load", "fail-start")) {
            assertThat(entry(app, id).configuredEnabled()).as(id).isFalse();
            assertThat(app.getBean(PluginRuntimeManager.class).loadedDescriptor(id)).as(id).isEmpty();
            uploads.probeVersion(id, 404);
        }
        assertThat(app.getBean(ExternalPluginInstaller.class).listInstalled()).noneMatch(p -> p.id().equals("backend-remove"));
        uploads.probeVersion("rollback", 404);
    }

    private static void checkManagementPage(Uploads uploads, String phase) throws Exception {
        var browser = new ProcessBuilder("node", "--test", "--test-name-pattern", "live management HTTP",
                System.getenv("PLUGIN_MANAGE_PAGE_TEST")).redirectErrorStream(true).inheritIO();
        browser.environment().put("PLUGIN_MANAGE_TEST_PORT", Integer.toString(uploads.port()));
        browser.environment().put("PLUGIN_MANAGE_TEST_SESSION", uploads.session());
        browser.environment().put("PLUGIN_MANAGE_TEST_PHASE", phase);
        var child = browser.start();
        try {
            assertThat(child.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isZero();
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor();
        }
    }

    private static void checkDuplicateAdmission(ConfigurableApplicationContext app, Uploads uploads, boolean restart) throws Exception {
        if (!restart) trustTestPublisher(app);
        for (boolean conflict : List.of(false, true)) {
            String provider = conflict ? "ui-conflict" : "ui-one-admitted";
            String consumer = provider + "-consumer";
            if (!restart) {
                uploads.stored(provider, "1.0.0", DESKTOP, false);
                uploads.deferred(consumer, "1.0.0", requires(provider));
                assertThat(entry(app, provider).effectiveAfterRestart()).isTrue();
                Path old = probe(Path.of("plugins"), consumer, "0.9.0", requires(provider));
                if (conflict) PluginTestProvenance.writeVerifiedLocalUpload(Path.of("plugins"), old);
                assertThat(uploads.impact(provider).at("/plugin/effectiveAfterRestart").asBoolean()).as(provider).isEqualTo(!conflict);
                var artifact = app.getBean(ExternalPluginInstaller.class).listInstalled().stream()
                        .filter(item -> item.id().equals(provider)).findFirst().orElseThrow().path();
                assertMarketRestart(app, provider, artifact, !conflict);
            } else {
                var status = app.getBean(PluginRuntimeManager.class).status().orElseThrow();
                if (conflict) {
                    assertThat(status.failures()).anyMatch(f -> f.reason().contains("duplicate plugin id " + consumer));
                    uploads.probeVersion(consumer, 404);
                    uploads.probeVersion(provider, 404);
                } else {
                    assertThat(status.failures()).anyMatch(f -> f.source().contains(consumer) && f.reason().contains("SIGNATURE_REQUIRED"));
                    assertThat(uploads.probeVersion(consumer, 200)).isEqualTo("1.0.0");
                    assertThat(uploads.probeVersion(provider, 200)).isEqualTo("1.0.0");
                }
            }
        }
    }

    private static void assertMarketRestart(ConfigurableApplicationContext app, String id, Path artifact, boolean needed) throws Exception {
        var version = top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader.inspect(artifact).descriptor().version();
        var pkg = new PluginCatalogPackage(version, "https://fixture.invalid/" + id + ".jar", Files.size(artifact),
                PluginPackageIntegrity.sha256Hex(artifact), null, null, null, List.of(), null, List.of(), "stable", false);
        var item = new PluginCatalogEntry(id, id, "plugin.name", null, null, List.of(pkg));
        var catalog = org.mockito.Mockito.mock(PluginCatalogService.class);
        org.mockito.Mockito.when(catalog.loadEntryPage(org.mockito.ArgumentMatchers.any(PluginRepository.class), org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(24)))
                .thenReturn(new PluginCatalogDetailPage(item, "fixture", null, 1L, false));
        var props = new PluginCatalogProperties();
        props.setEnabled(true);
        var market = new PluginMarketService(new PluginRepositoryRegistry(props), catalog, null,
                app.getBean(PluginStatusService.class), null, null, app.getBean(ExternalPluginInstaller.class),
                app.getBean(ExternalPluginLifecycleCoordinator.class));
        assertThat(market.pluginDetail("official", id).packages()).singleElement()
                .satisfies(projected -> assertThat(projected.effectiveAfterRestart()).isEqualTo(needed));
    }

    private static void checkExportRetry(ConfigurableApplicationContext app, Uploads uploads, String[] options) throws Exception {
        trustTestPublisher(app);
        Path state = Files.createDirectories(Path.of("state/gui")).resolve("window-state.json");
        System.setProperty("test.stop.output", state.toString());
        System.setProperty("test.stop.buffered-only", "true");
        uploads.signed("writer-leaf", "1.0.0", "pixiv.configuration-classes=" + StartupRepairProbePlugin.ShutdownConfig.class.getName(), 200);
        var writer = app.getBean(PluginLifecycleService.class).contextFor("writer-leaf").orElseThrow().getBean(BufferedWriter.class);
        var host = org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.class);
        org.mockito.Mockito.when(host.applicationVersion()).thenReturn("fixture");
        var snapshots = new AtomicInteger();
        var restart = new CompletableFuture<Void>();
        var service = new EnvironmentExportService(app, options, session -> {
            snapshots.incrementAndGet();
            return new EnvironmentTransferService(host, List::of, () -> true, session, options);
        }, () -> restart.complete(null));
        org.springframework.test.util.ReflectionTestUtils.setField(
                app.getBean(top.sywyar.pixivdownload.transfer.EnvironmentTransferController.class), "exports", service);
        String exportPath = "/api/settings/environment/exports";
        Path archive = Path.of("writer.pdbackup").toAbsolutePath();
        java.util.function.Supplier<EnvironmentExportService.Request> request = () -> new EnvironmentExportService.Request(
                archive.toString(), "fixture".toCharArray(), false, false, true, false, true);
        System.setProperty("test.stop.failures.writer-leaf", "-1");
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(uploads.writeJson("POST", exportPath, request.get(), 202).path("state").asString()).isEqualTo("ACCEPTED");
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (service.current().state() != EnvironmentExportService.State.FAILED && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(uploads.getJson(exportPath, "en-US", 200).path("failureCode").asString()).isEqualTo("transfer.backend-not-stopped");
            var failed = uploads.impact("writer-leaf").path("plugin");
            assertThat(failed.path("operation").asString()).isEqualTo("FAILED");
            assertThat(failed.path("operationDiagnostic").asString()).isEqualTo("BACKEND_STOP_FAILED");
            assertThat(failed.path("availableActions").size()).isZero();
            assertThat(failed.at("/trust/revocable").asBoolean()).isFalse();
            assertThat(uploads.impact("healthy").path("plugin").path("runtimePhase").asString()).isEqualTo("STOPPED");
            assertThat(uploads.action("healthy", "start", 409).path("code").asString()).isEqualTo("BACKEND_STOP_FAILED");
            assertThat(uploads.writeJson("PUT", "/api/plugins/healthy/enabled", java.util.Map.of("enabled", false), 409)
                    .path("code").asString()).isEqualTo("BACKEND_STOP_FAILED");
            assertThat(uploads.writeJson("DELETE", "/api/plugins/writer-leaf/trust", null, 409)
                    .path("code").asString()).isEqualTo("BACKEND_STOP_FAILED");
            assertThat(app.isActive()).isTrue();
            assertThat(snapshots.get()).isZero();
            assertThat(restart.isDone()).isFalse();
            assertThat(archive).doesNotExist();
        }
        writer.write("{\"width\":1367,\"height\":900,\"maximized\":false}");
        assertThat(Files.size(state)).isZero();
        System.clearProperty("test.stop.failures.writer-leaf");
        uploads.writeJson("POST", exportPath, request.get(), 202);
        restart.get(60, TimeUnit.SECONDS);
        assertThat(service.current().state()).isEqualTo(EnvironmentExportService.State.SUCCEEDED);
        assertThat(snapshots.get()).isOne();
        var reader = new TransferArchive();
        char[] password = "fixture".toCharArray();
        var preview = reader.inspect(archive, password);
        var item = preview.items().stream().filter(i -> i.descriptor().path().equals("window/window-state.json")).findFirst().orElseThrow();
        int index = preview.items().indexOf(item);
        try (var contents = reader.readSelected(archive, password, preview, java.util.Set.of(index))) {
            assertThat(JsonMapper.shared().readTree(contents.get(index)).path("width").asInt()).isEqualTo(1367);
        }
    }

    private static void rejectHotUpdateThatClosesDiskCycle(ConfigurableApplicationContext app, PluginRuntimeManager runtime,
                                                          Uploads uploads) throws Exception {
        long oldUpdates = runtime.generation("updates").orElseThrow();
        uploads.deferred("backend", "1.1.0", requires("desktop@1.0"));
        var cycle = uploads.post("updates", "1.1.0", requires("bridge@1.0"), 409);
        assertThat(cycle.path("dependencyProblems").get(0).path("reason").asString()).isEqualTo("CYCLE");
        assertThat(runtime.generation("updates")).contains(oldUpdates);
        assertThat(entry(app, "updates").version()).isEqualTo("1.0.0");
        var healthy = uploads.post("healthy", "1.1.0", requires("bridge@1.0"), 200);
        assertThat(healthy.path("activated").asBoolean()).isTrue();
    }

    private static void restoreServingVersionAfterFailedUpdate(ConfigurableApplicationContext app, PluginRuntimeManager runtime,
                                                               Uploads uploads) throws Exception {
        uploads.deferred("rollback", "1.1.0", requires("desktop@1.0"));
        if (System.getProperty("os.name").startsWith("Windows")) {
            var disk = app.getBean(ExternalPluginInstaller.class).listInstalled().stream()
                    .filter(plugin -> plugin.id().equals("rollback")).findFirst().orElseThrow().path();
            String digest = PluginPackageIntegrity.sha256Hex(disk);
            try (var held = java.nio.channels.FileChannel.open(disk, java.nio.file.StandardOpenOption.READ,
                    com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        app.getBean(top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator.class)
                                .remove("rollback")).hasRootCauseInstanceOf(java.nio.file.FileSystemException.class);
                assertServingOldVersion(app, "rollback", "1.1.0");
                assertThat(PluginPackageIntegrity.sha256Hex(disk)).isEqualTo(digest);
                assertThat(uploads.probeVersion("rollback", 200)).isEqualTo("1.0.0");
            }
        }
        long serving = runtime.generation("rollback").orElseThrow();
        var failed = uploads.post("rollback", "7.0.0", "", 500);
        assertThat(failed.path("rolledBack").asBoolean()).as(failed.toString()).isTrue();
        assertThat(failed.path("rollbackVersion").asString()).isEqualTo("1.0.0");
        assertThat(failed.path("previousVersion").asString()).isEqualTo("1.1.0");
        assertThat(runtime.generation("rollback").orElseThrow()).isGreaterThan(serving);
        var retained = entry(app, "rollback");
        assertThat(retained.version()).isEqualTo("1.1.0");
        assertThat(retained.loadedVersion()).isEqualTo("1.0.0");
        assertThat(retained.effectiveAfterRestart()).isTrue();
        assertThat(retained.runtimePhase().name()).isEqualTo("STARTED");
        assertThat(app.getBean(PluginRegistry.class).registeredPlugins()).anyMatch(p -> p.id().equals("rollback"));
    }

    private static void checkProcessRestartProviderVersions(ConfigurableApplicationContext app, Uploads uploads) throws Exception {
        uploads.deferred("provider", "2.0.0", RESTART);
        var compatible = uploads.post("both-consumer", "1.0.0", requires("provider"), 200);
        assertThat(compatible.path("activated").asBoolean()).isTrue();
        assertThat(compatible.path("effectiveAfterRestart").asBoolean()).isFalse();
        assertThat(compatible.path("dependencyProblems").size()).isZero();
        uploads.stored("desktop-consumer", "1.0.0", DESKTOP + "\n" + requires("provider@2.0"), false);
        var incompatible = uploads.post("old-consumer", "1.0.0", requires("provider@1.0"), 409);
        assertThat(incompatible.path("dependencyProblems").get(0).path("reason").asString()).isEqualTo("VERSION_UNSATISFIED");
        assertThat(app.getBean(ExternalPluginInstaller.class).listInstalled()).noneMatch(p -> p.id().equals("old-consumer"));
        uploads.deferred("consumer-root", "1.0.0", requires("desktop-consumer@1.0"));
    }

    private static PluginBootstrapSession restartWithPublisherKey() throws Exception {
        return PluginBootstrapSession.createContext(Path.of("plugins"), PluginEnabledSnapshot.empty(), publisherVerifier()).start();
    }

    private static PluginSupplyChainVerifier publisherVerifier() throws Exception {
        var key = JsonMapper.shared().readValue(Files.readString(Path.of("input/publisher.json")), TrustedPluginKey.class);
        return new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(key)));
    }

    private static void stageReplacementChains() throws Exception {
        Path plugins = Path.of("plugins");
        for (int i = 0; i < 4; i++) {
            String base = "base-" + i;
            for (var jar : List.of(probe(plugins, base, "1.0.0", i < 2 ? RESTART : ""),
                    probe(plugins, "child-" + i, "1.0.0", RESTART + "\n" + requires(base)))) {
                PluginTestProvenance.writeVerifiedLocalUpload(plugins, jar);
            }
        }
        Files.writeString(Path.of("input/publisher.json"), JsonMapper.shared().writeValueAsString(PluginTestProvenance.trustedKey()));
    }

    private static void checkReplacementChains(ConfigurableApplicationContext app, Uploads uploads, boolean restart) throws Exception {
        var runtime = app.getBean(PluginRuntimeManager.class);
        if (!restart) {
            app.getBean(PluginBootstrapSession.class).updateVerifier(PluginTestProvenance.verifier());
            mergePreviousDependency(uploads);
            uploads.signed("merge-kept-lib", "1.0.0", "", 200);
            uploads.signed("merge-deferred", "1.0.0", requires("merge-kept-lib"), 200);
            var deferred = uploads.signed("merge-deferred", "2.0.0", RESTART + "\npixiv.replaces=merge-kept-lib", 200);
            assertThat(deferred.path("effectiveAfterRestart").asBoolean()).isTrue();
            assertThat(uploads.probeVersion("merge-deferred", 200)).isEqualTo("1.0.0");
            assertThat(uploads.probeVersion("merge-kept-lib", 200)).isEqualTo("1.0.0");
        } else {
            assertThat(uploads.probeVersion("merge-deferred", 200)).isEqualTo("2.0.0");
            uploads.probeVersion("merge-kept-lib", 404);
        }
        for (int i = 0; i < 4; i++) {
            String base = "base-" + i, child = "child-" + i, bundle = "bundle-" + i;
            if (restart) {
                if (i == 3) {
                    assertThat(runtime.status().orElseThrow().startedPluginIds()).doesNotContain(base, child, bundle);
                    continue;
                }
                assertThat(runtime.status().orElseThrow().startedPluginIds()).contains(bundle).doesNotContain(base, child);
                assertThat(entry(app, bundle).effectiveAfterRestart()).isFalse();
                assertThat(runtime.loadedDescriptor(bundle).orElseThrow().version()).isEqualTo(i < 2 ? "2.1.0" : "2.0.0");
                if (i == 1) {
                    assertThat(uploads.probeVersion(bundle, 200)).isEqualTo("2.1.0");
                    assertThat(uploads.probeVersion("final-bundle", 200)).isEqualTo("1.0.0");
                    assertThat(entry(app, "final-bundle").effectiveAfterRestart()).isFalse();
                }
                continue;
            }
            long baseGeneration = runtime.generation(base).orElseThrow(), childGeneration = runtime.generation(child).orElseThrow();
            String replaces = "pixiv.replaces=" + (i % 2 == 0 ? base + "," + child : child + "," + base);
            if (i == 2) {
                assertThat(uploads.action(base, "reload", 409).path("code").asString()).isEqualTo("DEPENDENCY_BLOCKED");
            }
            if (i == 0) {
                uploads.post("outside-chain", "1.0.0", requires(base), 200);
                assertThat(uploads.signed(bundle, "2.0.0", replaces, 409).path("outcome").asString()).isEqualTo("REJECTED_DEPENDENCY");
                uploads.action("outside-chain", "remove", 200);
            }
            if (i == 3) replaces += "\npixiv.lifecycle-policy=backend-restart";
            var receipt = uploads.signed(bundle, "2.0.0", replaces, 200);
            assertThat(receipt.path("outcome").asString()).isEqualTo("INSTALLED");
            assertThat(receipt.path("effectiveAfterRestart").asBoolean()).isTrue();
            assertThat(receipt.path("activated").asBoolean()).isFalse();
            if (i == 0) {
                var update = uploads.signed(bundle, "2.1.0", "", 200);
                assertThat(update.path("effectiveAfterRestart").asBoolean()).isTrue();
                assertThat(update.path("activated").asBoolean()).isFalse();
                assertThat(runtime.loadedDescriptor(bundle)).isEmpty();
                assertThat(uploads.action(bundle, "start", 409).path("code").asString()).isEqualTo("RESTART_REQUIRED_PLUGIN");
            }
            if (i == 1) {
                var last = uploads.signed("final-bundle", "1.0.0", "pixiv.replaces=" + bundle, 200);
                assertThat(last.path("effectiveAfterRestart").asBoolean()).isTrue();
                var middleAgain = uploads.signed(bundle, "2.1.0", "", 200);
                assertThat(middleAgain.path("activated").asBoolean()).as("reinstalled middle activated").isFalse();
                assertThat(middleAgain.path("effectiveAfterRestart").asBoolean()).isTrue();
                for (String pending : List.of(bundle, "final-bundle")) {
                    assertThat(uploads.action(pending, "start", 409).path("code").asString()).isEqualTo("RESTART_REQUIRED_PLUGIN");
                    assertThat(runtime.loadedDescriptor(pending)).isEmpty();
                }
            }
            assertThat(runtime.generation(base)).contains(baseGeneration);
            assertThat(runtime.generation(child)).contains(childGeneration);
            for (String old : List.of(base, child)) {
                var state = entry(app, old);
                assertThat(state.runtimePhase().name()).isEqualTo("STARTED");
                assertThat(state.loadedVersion()).isEqualTo("1.0.0");
                assertThat(state.version()).isNull();
                assertThat(state.effectiveAfterRestart()).isTrue();
            }
            assertThat(entry(app, bundle).availableActions()).containsExactly("remove");
            assertThat(uploads.action(bundle, "load", 409).path("code").asString()).isEqualTo("RESTART_REQUIRED_PLUGIN");
            if (i == 3) {
                uploads.action(bundle, "remove", 200);
                assertThat(app.getBean(ExternalPluginInstaller.class).listInstalled())
                        .noneMatch(p -> List.of(bundle, base, child).contains(p.id()));
                assertThat(runtime.generation(base)).contains(baseGeneration);
                assertThat(runtime.generation(child)).contains(childGeneration);
                for (String old : List.of(base, child)) assertThat(uploads.probeVersion(old, 200)).isEqualTo("1.0.0");
            }
        }
        assertThat(runtime.status().orElseThrow().failures()).isEmpty();
    }

    private static void trustTestPublisher(ConfigurableApplicationContext app) throws Exception {
        Files.writeString(Path.of("input/publisher.json"), JsonMapper.shared().writeValueAsString(PluginTestProvenance.trustedKey()));
        app.getBean(PluginBootstrapSession.class).updateVerifier(PluginTestProvenance.verifier());
    }

    private static void checkRestartPolicyAndImpact(ConfigurableApplicationContext app, PluginRuntimeManager runtime,
                                                   Uploads uploads) throws Exception {
        uploads.post("library", "1.0.0", "", 200);
        uploads.post("library-client", "1.0.0", requires("library"), 200);
        long generation = runtime.generation("library-client").orElseThrow();
        uploads.deferred("library-client", "1.1.0", RESTART);
        assertThat(entry(app, "library-client").availableActions()).containsExactly("remove");
        assertThat(uploads.action("library-client", "reload", 409).path("code").asString()).isEqualTo("RESTART_REQUIRED_PLUGIN");
        assertServingOldVersion(app, "library-client", "1.1.0");
        assertThat(runtime.generation("library-client")).contains(generation);

        var consumers = uploads.impact("library").path("consumers");
        assertThat(consumers.size()).isOne();
        var client = consumers.get(0);
        assertThat(client.path("id").asString()).isEqualTo("library-client");
        assertThat(client.path("loadedDependencies").get(0).path("pluginId").asString()).isEqualTo("library");
        assertThat(client.path("dependencies").size()).isZero();
        assertThat(uploads.action("library", "remove", 409).path("code").asString()).isEqualTo("DEPENDENCY_BLOCKED");
    }

    private static void preserveAdminPhaseOnRollback(ConfigurableApplicationContext app, Uploads uploads) throws Exception {
        String taskConfig = "pixiv.configuration-classes=" + StartupRepairProbePlugin.StartupTaskConfig.class.getName();
        String provider = "quiet-provider", client = "quiet-client";
        uploads.signed(provider, "1.0.0", taskConfig, 200);
        uploads.signed(client, "1.0.0", requires(provider) + "\n" + taskConfig, 200);
        for (String owner : List.of(client, provider)) uploads.action(owner, "quiesce", 200);
        var rollback = uploads.signed("quiet-bundle", "7.0.0", "pixiv.replaces=" + provider + "," + client, 500);
        assertThat(rollback.path("rolledBack").asBoolean()).as(rollback.toString()).isTrue();
        for (String owner : List.of(provider, client)) {
            var previous = Path.of("input", owner + "-1.0.0.jar");
            var artifact = app.getBean(PluginRuntimeManager.class).artifactPath(owner).orElseThrow();
            assertThat(PluginPackageIntegrity.sha256Hex(artifact)).isEqualTo(PluginPackageIntegrity.sha256Hex(previous));
            var restored = uploads.impact(owner).path("plugin");
            assertThat(restored.path("version").asString()).isEqualTo("1.0.0");
            assertThat(app.getBean(PluginRuntimeManager.class).loadedDescriptor(owner).orElseThrow().version()).isEqualTo("1.0.0");
            assertThat(restored.path("runtimePhase").asString()).isEqualTo("QUIESCED");
            uploads.probeVersion(owner, 503);
            var context = app.getBean(PluginLifecycleService.class).contextFor(owner).orElseThrow();
            assertThat(context.getBean("startupTaskRuns", AtomicInteger.class)).hasValue(0);
            assertThat(context.getBean("startupStreamCloses", AtomicInteger.class)).hasValue(1);
        }
        uploads.action(client, "stop", 200);
        assertThat(uploads.action(client, "start", 409).path("code").asString()).isEqualTo("DEPENDENCY_UNSATISFIED");
        uploads.action(provider, "restart", 200);
        assertThat(uploads.probeVersion(provider, 200)).isEqualTo("1.0.0");
        uploads.action(client, "start", 200);
        assertThat(uploads.probeVersion(client, 200)).isEqualTo("1.0.0");

        String id = "idle-original";
        uploads.signed(id, "1.0.0", "pixiv.configuration-classes=" + StartupRepairProbePlugin.StartupTaskConfig.class.getName(), 200);
        var lifecycle = app.getBean(PluginLifecycleService.class);
        assertThat(lifecycle.contextFor(id).orElseThrow().getBean("startupTaskRuns", AtomicInteger.class)).hasValue(1);
        var runtime = app.getBean(PluginRuntimeManager.class);
        var pf4j = (org.pf4j.PluginManager) org.springframework.test.util.ReflectionTestUtils.getField(runtime, "pluginManager");
        assertThat(uploads.signed(id, "1.1.0", requires("desktop"), 200).path("effectiveAfterRestart").asBoolean()).isTrue();
        for (String phase : List.of("STOPPED", "QUIESCED")) {
            assertThat(uploads.probeVersion(id, 200)).isEqualTo("1.0.0");
            uploads.action(id, phase.equals("STOPPED") ? "stop" : "quiesce", 200);
            for (String candidate : List.of(id, "idle-replacement")) {
                var result = uploads.signed(candidate, "7.0.0", candidate.equals(id) ? "" : "pixiv.replaces=" + id, 500);
                assertThat(result.path("rolledBack").asBoolean()).as(result.toString()).isTrue();
                assertThat(result.path("rollbackVersion").asString()).isEqualTo("1.0.0");
                var restored = entry(app, id);
                assertThat(restored.runtimePhase().name()).isEqualTo(phase);
                assertThat(restored.version()).isEqualTo("1.1.0");
                assertThat(restored.loadedVersion()).isEqualTo("1.0.0");
                assertThat(restored.effectiveAfterRestart()).isTrue();
                var registry = app.getBean(PluginRegistry.class);
                var registration = registry.registeredPlugins().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
                boolean quiesced = phase.equals("QUIESCED");
                assertThat(registry.featureStarted(registration)).isEqualTo(quiesced);
                if (quiesced) {
                    assertThat(pf4j.getPlugin(id).getPluginState()).isEqualTo(org.pf4j.PluginState.STARTED);
                    uploads.probeVersion(id, 503);
                    var child = lifecycle.contextFor(id).orElseThrow();
                    assertThat(child.isActive()).isTrue();
                    assertThat(child.getBean("startupTaskRuns", AtomicInteger.class)).as("restored task runs").hasValue(0);
                }
            }
            if (phase.equals("STOPPED")) uploads.action(id, "start", 200);
        }
        System.setProperty("test.plugin.fail-start", id + "@1.0.0");
        try {
            var failed = uploads.signed(id, "7.0.0", "", 500);
            assertThat(failed.get("rolledBack").asBoolean()).as(failed.toString()).isFalse();
            assertThat(pf4j.getPlugin(id).getPluginState()).isNotEqualTo(org.pf4j.PluginState.STARTED);
        } finally {
            System.clearProperty("test.plugin.fail-start");
        }
    }

    private static void checkDesktopRestart(String[] options) throws Exception {
        var backend = new AtomicReference<ConfigurableApplicationContext>();
        var starts = new AtomicInteger();
        var failed = new CompletableFuture<Throwable>();
        BackendLifecycleManager.Listener listener = snapshot -> {
            if (snapshot.state() == BackendLifecycleManager.State.FAILED) failed.complete(snapshot.error());
        };
        var releaseTask = new CompletableFuture<Void>();
        String fault = "test.stop.failures.writer-leaf";
        try (var session = PluginBootstrapSession.createProcess(Path.of("plugins"), PluginEnabledSnapshot.empty(), publisherVerifier()).start();
             var registration = BackendLifecycleManager.configure(options, failed::complete, args -> {
                 starts.incrementAndGet();
                 var app = PixivDownloadApplication.start(args, session);
                 backend.set(app);
                 return app;
             })) {
            BackendLifecycleManager.addListener(listener);
            try {
                var running = new CompletableFuture<Void>();
                assertThat(BackendLifecycleManager.startAsync(() -> running.complete(null))).isTrue();
                running.get(60, TimeUnit.SECONDS);
                var parent = backend.get();
                var lifecycle = parent.getBean(PluginLifecycleService.class);
                var leaf = lifecycle.contextFor("writer-leaf").orElseThrow();
                var peer = lifecycle.contextFor("writer-hot").orElseThrow();
                var middle = lifecycle.contextFor("writer-mid").orElseThrow();
                var writer = leaf.getBean(BufferedWriter.class);
                var peerWriter = peer.getBean(BufferedWriter.class);
                var tasks = leaf.getBean(PluginRuntimeTaskRegistrar.class);
                var entered = new CompletableFuture<Void>();
                var task = CompletableFuture.runAsync(tasks.registerOneShot(() -> {
                    entered.complete(null);
                    releaseTask.join();
                }));
                entered.get(5, TimeUnit.SECONDS);
                System.setProperty(fault, "-1");
                var restarted = new CompletableFuture<Void>();
                assertThat(BackendLifecycleManager.restartAsync(() -> restarted.complete(null))).isTrue();
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (tasks.acceptsNewTasks() && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(tasks.acceptsNewTasks()).isFalse();
                assertThat(leaf.isActive()).isTrue();
                assertThat(Integer.getInteger("test.stop.attempts.writer-leaf", 0)).isZero();
                assertThat(BackendLifecycleManager.state()).isEqualTo(BackendLifecycleManager.State.STOPPING);
                releaseTask.complete(null);
                task.get(5, TimeUnit.SECONDS);
                CompletableFuture.anyOf(failed, restarted).get(60, TimeUnit.SECONDS);
                assertThat(BackendLifecycleManager.state()).isEqualTo(BackendLifecycleManager.State.FAILED);
                assertThat(failed.join()).hasRootCauseMessage("stop failure for writer-leaf");
                assertThat(Integer.getInteger("test.stop.attempts.writer-leaf")).isEqualTo(2);
                assertThat(restarted).isNotDone();
                assertThat(starts).hasValue(1);
                assertThat(BackendLifecycleManager.owns(parent)).isTrue();
                assertThat(BackendLifecycleManager.startAsync()).isFalse();
                assertThat(leaf.isActive()).isTrue();
                writer.write("retry pending\n");
                writer.flush();
                assertThat(Files.readAllLines(Path.of("stop.log"))).contains("retry pending");
                assertThat(peer.isActive()).isFalse();
                assertThat(middle.isActive()).isFalse();
                assertWriterClosed(peerWriter);
                var toolFailure = new CompletableFuture<Throwable>();
                assertThat(BackendLifecycleManager.stopAsync(() -> toolFailure.complete(null), toolFailure::complete)).isTrue();
                assertThat(toolFailure.get(60, TimeUnit.SECONDS)).hasRootCauseMessage("stop failure for writer-leaf");
                assertThat(starts).hasValue(1);
                assertThat(leaf.isActive()).isTrue();

                System.clearProperty(fault);
                restartDesktop();
                assertThat(starts).hasValue(2);
                assertThat(parent.isActive()).isFalse();
                assertThat(leaf.isActive()).isFalse();
                assertWriterClosed(writer);
                var secondLeaf = backend.get().getBean(PluginLifecycleService.class).contextFor("writer-leaf").orElseThrow();
                var secondWriter = secondLeaf.getBean(BufferedWriter.class);
                System.setProperty(fault, "1");
                restartDesktop();
                assertThat(starts).hasValue(3);
                assertThat(secondLeaf.isActive()).isFalse();
                assertWriterClosed(secondWriter);
                assertThat(Integer.getInteger("test.stop.attempts.writer-leaf")).isEqualTo(5);
                assertThat(backend.get().getBean(PluginLifecycleService.class).contextFor("writer-leaf").orElseThrow().isActive()).isTrue();
            } finally {
                System.clearProperty(fault);
                releaseTask.complete(null);
                BackendLifecycleManager.removeListener(listener);
                assertThat(BackendLifecycleManager.closeBackendContext(60_000).result().isContextConfirmedClosed()).isTrue();
            }
        }
    }

    private static void assertWriterClosed(BufferedWriter writer) {
        assertThat(catchThrowable(() -> {
            writer.write("closed");
            writer.flush();
        })).isInstanceOf(IOException.class);
    }

    private static void restartDesktop() throws Exception {
        var restarted = new CompletableFuture<Void>();
        assertThat(BackendLifecycleManager.restartAsync(() -> restarted.complete(null))).isTrue();
        restarted.get(60, TimeUnit.SECONDS);
        assertThat(BackendLifecycleManager.snapshot()).isEqualTo(new BackendLifecycleManager.Snapshot(BackendLifecycleManager.State.RUNNING, null));
    }

    private static void checkQuiescedPush(ConfigurableApplicationContext app, Uploads uploads) throws Exception {
        trustTestPublisher(app);
        String id = "push-owner";
        uploads.signed(id, "1.0.0", "pixiv.configuration-classes=" + StartupRepairProbePlugin.PushConfig.class.getName(), 200);
        var registry = app.getBean(PushChannelRegistry.class);
        var channel = new PushChannelId("restore-probe");
        var message = new RenderedMessage("probe", "", null, null);
        Runnable send = () -> registry.byId(channel).orElseThrow().send(message);
        send.run();
        uploads.action(id, "quiesce", 200);
        assertThat(catchThrowable(send::run)).isInstanceOf(ExternalCapabilityUnavailableException.class);

        var lifecycle = app.getBean(PluginLifecycleService.class);
        String field = "afterCapabilityPublishReturnProbe";
        var originalProbe = ReflectionTestUtils.getField(lifecycle, field);
        var during = new AtomicReference<Throwable>();
        var calls = new AtomicInteger();
        Runnable probe = () -> {
            calls.incrementAndGet();
            during.set(CompletableFuture.supplyAsync(() -> catchThrowable(send::run)).orTimeout(5, TimeUnit.SECONDS).join());
        };
        ReflectionTestUtils.setField(lifecycle, field, probe);
        try {
            var result = uploads.signed("push-replacement", "7.0.0", "pixiv.replaces=" + id, 500);
            assertThat(result.path("rolledBack").asBoolean()).as(result.toString()).isTrue();
        } finally {
            ReflectionTestUtils.setField(lifecycle, field, originalProbe);
        }
        assertThat(calls).hasValue(1);
        assertThat(during.get()).isInstanceOf(ExternalCapabilityUnavailableException.class);
        assertThat(entry(app, id).runtimePhase().name()).isEqualTo("QUIESCED");
        assertThat(catchThrowable(send::run)).isInstanceOf(ExternalCapabilityUnavailableException.class);
        assertThat(Integer.getInteger("test.push.sends")).isOne();
        uploads.action(id, "restart", 200);
        send.run();
        assertThat(Integer.getInteger("test.push.sends")).isEqualTo(2);
    }

    private static void checkRecoveryGate(ConfigurableApplicationContext app, Uploads uploads, boolean restart) throws Exception {
        String root = "sentinel-root", middle = "sentinel-middle", leaf = "recovery-sentinel";
        if (!restart) {
            checkRecoveryMessages(uploads, "MISSING_REQUIRED", "plugin.recovery.blocked");
            trustTestPublisher(app);
            if (Files.isRegularFile(Path.of("input/gallery.jar"))) uploads.upload(Path.of("input/gallery.jar"), null, 200);
            uploads.signed(root, "1.0.0", "pixiv.configuration-classes=" + StartupRepairProbePlugin.ContextProbe.class.getName(), 200);
            uploads.signed("sentinel-peer", "1.0.0", "", 200);
            uploads.signed(middle, "1.0.0", requires(root + ",sentinel-peer"), 200);
            uploads.signed("sentinel-optional", "1.0.0", "", 200);
            uploads.signed(leaf, "1.0.0", requires(middle + ",sentinel-optional?"), 200);
            uploads.action("sentinel-optional", "quiesce", 200);
            assertThat(uploads.status().path("recoveryMode").asBoolean()).isFalse();
            uploads.action(root, "quiesce", 200);
            uploads.action("sentinel-peer", "quiesce", 200);
            var blockers = uploads.status().at("/recoveryReasons/0/blockingProviders");
            assertThat(blockers.size()).isEqualTo(2);
            assertThat(blockers.path(root).asString()).isEqualTo("QUIESCED");
            assertThat(blockers.path("sentinel-peer").asString()).isEqualTo("QUIESCED");
            var recovery = uploads.getManagement("recovery");
            assertThat(recovery.at("/focus/plugins/" + root).asString()).isEqualTo("dependency");
            assertThat(recovery.at("/focus/plugins/sentinel-peer").asString()).isEqualTo("dependency");
            assertThat(recovery.at("/focus/plugins").has("sentinel-optional")).isFalse();
            assertThat(recovery.path("advice").asString()).contains(root, "sentinel-peer", "Quiesced");
            assertThat(recovery.path("errors").toString()).contains(root, "sentinel-peer");
            checkRecoveryMessages(uploads, "STARTED", "plugin.recovery.not-serving");
            uploads.probeVersion(leaf, 302);
            assertThat(entry(app, root).availableActions()).contains("restart");
            uploads.action(root, "restart", 200);
            var remaining = uploads.getManagement("recovery");
            assertThat(remaining.path("active").asBoolean()).isTrue();
            assertThat(remaining.at("/focus/plugins").has(root)).isFalse();
            assertThat(remaining.path("advice").asString()).contains("sentinel-peer").doesNotContain(root);
            uploads.action("sentinel-peer", "restart", 200);
            assertThat(app.getBean(PluginRuntimeManager.class).status().orElseThrow().startedPluginIds()).contains(root, middle, leaf);
            assertThat(uploads.status().path("recoveryMode").asBoolean()).isFalse();
            assertThat(uploads.probeVersion(leaf, 200)).isEqualTo("1.0.0");
            return;
        }
        app.getBean(PluginBootstrapSession.class).updateVerifier(publisherVerifier());
        assertThat(uploads.status().path("recoveryMode").asBoolean()).isTrue();
        checkRecoveryMessages(uploads, "MISSING_REQUIRED", "plugin.recovery.not-serving");
        System.clearProperty("test.plugin.fail-context");
        for (String id : List.of(root, middle)) {
            uploads.action(id, "start", 200);
            var status = uploads.status();
            assertThat(status.path("recoveryMode").asBoolean()).as(id + " started").isTrue();
            var sentinel = uploads.impact(leaf).path("plugin");
            assertThat(sentinel.path("status").asString()).isEqualTo("STARTED");
            assertThat(sentinel.path("runtimePhase").asString()).isEqualTo("STOPPED");
            assertThat(app.getBean(PluginRuntimeTaskRegistry.class).acceptsNewTasks(leaf)).isFalse();
            uploads.probeVersion(leaf, 302);
            checkRecoveryMessages(uploads, "STARTED", "plugin.recovery.not-serving");
        }
        uploads.action(leaf, "start", 200);
        assertThat(uploads.status().path("recoveryMode").asBoolean()).isFalse();
        assertThat(uploads.status().path("recoveryReasons").size()).isZero();
        var market = uploads.getJson("/api/plugins/recovery", "en-US", 200);
        assertThat(market.path("active").asBoolean()).isFalse();
        assertThat(market.path("focus").path("plugins").size()).isZero();
        for (String locale : List.of("zh-CN", "en-US", "ja-JP", "ko-KR", "zh-Hant")) {
            var api = uploads.getJson("/api/openapi.json", locale, 200);
            assertThat(api.path("paths").path("/api/plugins/status").has("get")).isTrue();
            assertThat(api.at("/components/schemas/top.sywyar.pixivdownload.plugin.runtime.status.RecoveryModeReason/properties/missingDependencyId/description").asString())
                    .contains("ID");
        }
        assertThat(uploads.getJson("/api/authors", "en-US", 200).isArray()).isTrue();
        assertThat(uploads.getJson("/api/artworks", "en-US", 404).path("code").asString()).isEqualTo("error.request.not-found");
        if (Files.isRegularFile(Path.of("input/gallery.jar"))) {
            assertThat(uploads.getJson("/api/gallery/artworks", "en-US", 200).path("content").isArray()).isTrue();
        }
        assertThat(uploads.probeVersion(leaf, 200)).isEqualTo("1.0.0");
    }

    private static void checkRecoveryMessages(Uploads uploads, String state, String key) throws Exception {
        for (String lang : List.of("en-US", "zh-CN")) {
            var reason = uploads.getJson("/api/plugins/status", lang, 200).path("recoveryReasons").get(0);
            assertThat(reason.path("pluginId").asString()).isEqualTo("recovery-sentinel");
            assertThat(reason.path("status").asString()).isEqualTo(state);
            var error = uploads.getJson("/api/artworks", lang, 503);
            assertThat(error.path("code").asString()).isEqualTo(key);
            assertThat(reason.path("messageKey").asString()).isEqualTo(key);
            String message = error.path("error").asString();
            assertThat(uploads.getJson("/api/authors", lang, 503)).isEqualTo(error);
            assertThat(uploads.getJson("/api/gallery/artworks", lang, 503)).isEqualTo(error);
            var market = uploads.getJson("/api/plugins/recovery", lang, 200);
            assertThat(market.path("active").asBoolean()).isTrue();
            assertThat(market.path("focus").path("plugins").has("recovery-sentinel")).isTrue();
            if (!key.equals("plugin.recovery.blocked")) {
                assertThat(market.path("advice").asString()).contains(message);
                assertThat(market.path("errors").valueStream().map(JsonNode::asString))
                        .anyMatch(detail -> detail.contains("recovery-sentinel") && detail.contains(message));
            }
            if (key.equals("plugin.recovery.missing-dependency")) {
                assertThat(reason.path("missingDependencyId").asString()).isEqualTo("sentinel-middle");
                assertThat(message).contains("sentinel-middle", lang.equals("en-US") ? "install" : "安装");
                assertThat(market.path("focus").path("plugins").has("sentinel-middle")).isTrue();
            } else if (key.equals("plugin.recovery.blocked")) {
                assertThat(message).contains(lang.equals("en-US") ? "install" : "安装");
            } else {
                assertThat(message).contains(lang.equals("en-US") ? "start" : "启动")
                        .contains(lang.equals("en-US") ? "dependencies" : "依赖");
            }
            assertThat(reason.path("messages").valueStream().map(JsonNode::asString)).contains(message);
        }
    }

    private static void checkServiceGraph(String phase, ConfigurableApplicationContext app, Uploads uploads) throws Exception {
        String base = "graph-base", mid = "graph-mid", leaf = "graph-leaf", peer = "graph-peer", optional = "graph-optional";
        String context = "pixiv.configuration-classes=" + StartupRepairProbePlugin.ContextProbe.class.getName();
        if (phase.equals("graph")) {
            trustTestPublisher(app);
            uploads.signed(base, "1.0.0", context, 200);
            uploads.signed(mid, "1.0.0", requires(base), 200);
            // 启动时会先枚举这个子 context，后枚举无需 context 的提供方。
            uploads.signed(leaf, "1.0.0", requires(mid) + "\npixiv.configuration-classes="
                    + StartupRepairProbePlugin.StartupTaskConfig.class.getName(), 200);
            uploads.signed(peer, "1.0.0", "", 200);
            uploads.signed(optional, "1.0.0", requires(base + "?"), 200);
            for (String id : List.of(base, mid, leaf, peer, optional)) {
                assertThat(uploads.probeVersion(id, 200)).isEqualTo("1.0.0");
                assertThat(Integer.getInteger("test.starts." + id, 0)).as(id).isOne();
            }
            assertThat(Boolean.getBoolean("test.graph.used")).isTrue();
            uploads.signed("graph-quiet", "1.0.0", "", 200);
            uploads.action("graph-quiet", "quiesce", 200);
            System.setProperty("test.plugin.fail-context", "true");
            var result = uploads.signed("graph-bundle", "7.0.0", "pixiv.replaces="
                    + String.join(",", peer, "graph-quiet", base, mid, leaf), 500);
            assertThat(result.path("outcome").asString()).isEqualTo("FAILED");
            assertThat(result.path("rolledBack").asBoolean()).isFalse();
            uploads.probeVersion("graph-quiet", 503);
        }
        assertThat(uploads.probeVersion(peer, 200)).isEqualTo("1.0.0");
        if (!phase.equals("graph-restart")) {
            for (String id : List.of(base, mid, leaf)) {
                uploads.probeVersion(id, 404);
                assertThat(entry(app, id).runtimePhase().name()).isNotEqualTo("STARTED");
            }
            assertThat(uploads.action(mid, "restart", 409).path("code").asString()).isEqualTo("DEPENDENCY_UNSATISFIED");
        }
        assertThat(uploads.probeVersion(optional, 200)).isEqualTo("1.0.0");
        if (phase.equals("graph")) {
            System.clearProperty("test.plugin.fail-context");
            long generation = entry(app, base).generation();
            Path fix = probe(Path.of("input"), base, "1.1.0", context);
            var signature = PluginTestProvenance.signArtifact(base, "1.1.0", Files.size(fix), PluginPackageIntegrity.sha256Hex(fix));
            var blocked = uploads.upload(fix, signature, 409);
            assertThat(blocked.path("outcome").asString()).isEqualTo("REJECTED_DEPENDENCY");
            assertThat(blocked.path("rolledBack").asBoolean()).isFalse();
            var conflict = blocked.path("dependencyProblems").get(0);
            assertThat(conflict.path("reason").asString()).isEqualTo("DEPENDENT_CONFLICT");
            assertThat(conflict.path("status").asString()).isEqualTo(mid);
            assertThat(entry(app, base).generation()).isEqualTo(generation);
            uploads.action(leaf, "unload", 200);
            uploads.action(mid, "unload", 200);
            assertThat(uploads.upload(fix, signature, 200).path("activated").asBoolean()).isTrue();
            for (String id : List.of(mid, leaf)) {
                uploads.action(id, "load", 200);
                uploads.action(id, "start", 200);
            }
            uploads.probeVersion("graph-quiet", 503);
        } else {
            assertThat(uploads.probeVersion("graph-quiet", 200)).isEqualTo("1.0.0");
            assertThat(app.getBean(PluginRegistry.class).registeredPlugins()).extracting(PluginRegistry.RegisteredPlugin::id)
                    .containsSubsequence(leaf, mid);
        }
        if (phase.equals("graph-broken-restart")) {
            var runtime = app.getBean(PluginRuntimeManager.class);
            var registry = app.getBean(PluginRegistry.class);
            assertThat(uploads.impact(base).path("plugin").path("status").asString()).isEqualTo("CRASHED");
            assertThat(runtime.packagePhases().get(base).name()).isEqualTo("CRASHED");
            assertThat(registry.lifecycleFailuresById()).containsOnlyKeys(base);
            assertThat(runtime.status().orElseThrow().failures()).filteredOn(f -> f.phase().equals("plugin-execution"))
                    .singleElement().satisfies(f -> assertThat(f.source()).isEqualTo(base));
            for (String id : List.of(mid, leaf)) {
                assertThat(Integer.getInteger("test.starts." + id, 0)).as(id).isZero();
                var blocked = uploads.impact(id).path("plugin");
                assertThat(blocked.path("status").asString()).isEqualTo("MISSING_REQUIRED");
                assertThat(blocked.path("messages").toString()).contains(id.equals(mid) ? base : mid);
                assertThat(blocked.path("runtimePhase").asString()).isEqualTo("STOPPED");
                assertThat(runtime.packagePhases().get(id).name()).isNotEqualTo("CRASHED");
                assertThat(app.getBean(PluginRuntimeTaskRegistry.class).acceptsNewTasks(id)).isFalse();
            }
            System.clearProperty("test.plugin.fail-context");
            app.getBean(PluginBootstrapSession.class).updateVerifier(publisherVerifier());
            for (String id : List.of(base, mid, leaf)) uploads.action(id, "start", 200);
            assertThat(registry.lifecycleFailuresById()).isEmpty();
            assertThat(runtime.status().orElseThrow().failures()).noneMatch(f -> f.phase().equals("plugin-execution"));
        }
        if (!phase.equals("graph")) {
            for (String id : List.of(base, mid, leaf, optional, peer)) {
                assertThat(Integer.getInteger("test.starts." + id, 0)).as(id).isOne();
                assertThat(entry(app, id).status().name()).as(id).isEqualTo("STARTED");
            }
            assertThat(Boolean.getBoolean("test.graph.used")).isTrue();
        }
        assertThat(uploads.probeVersion(base, 200)).isEqualTo("1.1.0");
        for (String id : List.of(mid, leaf, optional)) assertThat(uploads.probeVersion(id, 200)).isEqualTo("1.0.0");
    }

    private static void checkInitializationFailure(String phase, ConfigurableApplicationContext app, Uploads uploads) throws Exception {
        String signed = "init-signed", local = "init-local", consumer = "init-consumer";
        Path input = Path.of("input"), fixed = input.resolve(signed + "-1.1.0.jar"), proof = input.resolve("repair-signature.json");
        switch (phase) {
            case "init" -> {
                trustTestPublisher(app);
                uploads.signed(signed, "1.0.0", DESKTOP, 200);
                uploads.stored(local, "1.0.0", DESKTOP, false);
                probe(input, signed, "1.1.0", DESKTOP);
                Files.writeString(proof, JsonMapper.shared().writeValueAsString(PluginTestProvenance.signArtifact(
                        signed, "1.1.0", Files.size(fixed), PluginPackageIntegrity.sha256Hex(fixed))));
            }
            case "init-load-restart" -> {
                for (String id : List.of(local, signed)) {
                    assertThat(entry(app, id).runtimePhase()).isNull();
                    assertThat(entry(app, id).effectiveAfterRestart()).isFalse();
                }
                Path retry = probe(input, consumer, "1.0.0", RESTART + "\n" + requires(signed));
                assertThat(uploads.upload(retry, null, 200).path("outcome").asString()).isEqualTo("INSTALLED");
                assertThat(uploads.upload(retry, null, 200).path("outcome").asString()).isEqualTo("DUPLICATE");
                for (String id : List.of(local, signed)) {
                    System.setProperty("test.plugin.fail-init", id + "@1.0.0");
                    var failure = uploads.action(id, "load", 500);
                    assertThat(failure.path("code").asString()).isEqualTo("INITIALIZATION_FAILED");
                    var rejected = uploads.post(id + "-new", "1.0.0", requires(id), 409);
                    assertInitializationRejected(rejected, id);
                    if (id.equals(signed)) assertInitializationRejected(uploads.upload(retry, null, 409), id);
                    if (id.equals(local)) {
                        uploads.stored(local, "1.1.0", DESKTOP, false);
                        uploads.deferred("init-local-new", "1.0.0", requires(local));
                    }
                    System.clearProperty("test.plugin.fail-init");
                    uploads.action(id, "load", 200);
                    assertThat(entry(app, id).runtimePhase().name()).isEqualTo("LOADED");
                    if (id.equals(signed)) {
                        var duplicate = uploads.upload(retry, null, 200);
                        assertThat(duplicate.path("outcome").asString()).isEqualTo("DUPLICATE");
                        assertThat(duplicate.path("effectiveAfterRestart").asBoolean()).isTrue();
                        var first = uploads.post("init-signed-new", "1.0.0", requires(signed), 200);
                        assertThat(first.path("outcome").asString()).isEqualTo("INSTALLED");
                        assertThat(first.path("effectiveAfterRestart").asBoolean()).isTrue();
                        assertThat(first.path("activated").asBoolean()).isFalse();
                    }
                    uploads.action(id, "start", 200);
                    assertThat(uploads.probeVersion(id, 200)).isEqualTo(id.equals(local) ? "1.1.0" : "1.0.0");
                }
            }
            case "init-healthy-restart" -> {
                for (String id : List.of(signed, consumer, "init-signed-new")) {
                    assertThat(uploads.probeVersion(id, 200)).isEqualTo("1.0.0");
                }
            }
            case "init-broken-restart" -> {
                uploads.probeVersion(signed, 404);
                uploads.probeVersion(consumer, 404);
                assertThat(uploads.probeVersion(local, 200)).isEqualTo("1.1.0");
                assertThat(uploads.probeVersion("init-local-new", 200)).isEqualTo("1.0.0");
                var failures = app.getBean(PluginRuntimeManager.class).status().orElseThrow().failures();
                assertThat(failures).anyMatch(f -> f.source().contains(signed) && f.reason().contains("initialize"));
                assertThat(failures).anyMatch(f -> f.source().contains(consumer) && f.reason().contains(signed));
                var signature = JsonMapper.shared().readValue(Files.readString(proof), SignatureMetadata.class);
                assertThat(uploads.upload(fixed, signature, 200).path("effectiveAfterRestart").asBoolean()).isTrue();
                var duplicate = uploads.upload(input.resolve(consumer + "-1.0.0.jar"), null, 200);
                assertThat(duplicate.path("outcome").asString()).isEqualTo("DUPLICATE");
            }
            case "init-restart" -> {
                assertThat(uploads.probeVersion(signed, 200)).isEqualTo("1.1.0");
                assertThat(uploads.probeVersion(consumer, 200)).isEqualTo("1.0.0");
                assertThat(uploads.probeVersion("init-signed-new", 200)).isEqualTo("1.0.0");
            }
        }
    }

    private static void assertInitializationRejected(JsonNode result, String provider) {
        assertThat(result.path("outcome").asString()).isEqualTo("REJECTED_DEPENDENCY");
        assertThat(result.path("effectiveAfterRestart").asBoolean()).isFalse();
        var problem = result.path("dependencyProblems").get(0);
        assertThat(problem.path("pluginId").asString()).isEqualTo(provider);
        assertThat(problem.path("status").asString()).isEqualTo("INITIALIZATION_FAILED");
    }

    private static void mergePreviousDependency(Uploads uploads) throws Exception {
        String client = "merge-client", library = "merge-lib";
        uploads.signed(library, "1.0.0", "", 200);
        uploads.signed(client, "1.0.0", requires(library), 200);
        long serving = uploads.impact(library).path("plugin").path("generation").asLong();
        var occupied = uploads.signed(library, "1.1.0", "", 409);
        assertThat(occupied.path("rolledBack").asBoolean()).isFalse();
        assertThat(occupied.path("dependencyProblems").get(0).path("status").asString()).isEqualTo(client);
        assertThat(uploads.impact(library).path("plugin").path("generation").asLong()).isEqualTo(serving);
        assertThat(uploads.probeVersion(library, 200)).isEqualTo("1.0.0");
        String replacement = "pixiv.replaces=" + library;
        uploads.signed("merge-outside", "1.0.0", requires(library), 200);
        var blocked = uploads.signed(client, "2.0.0", replacement, 409);
        var conflict = blocked.path("dependencyProblems").get(0);
        assertThat(conflict.path("reason").asString()).isEqualTo("DEPENDENT_CONFLICT");
        assertThat(conflict.path("status").asString()).isEqualTo("merge-outside");
        assertThat(uploads.probeVersion(client, 200)).isEqualTo("1.0.0");
        uploads.action("merge-outside", "remove", 200);

        long libraryGeneration = uploads.impact(library).path("plugin").path("generation").asLong();
        long clientGeneration = uploads.impact(client).path("plugin").path("generation").asLong();
        var failed = uploads.signed(client, "7.0.0", replacement, 500);
        assertThat(failed.path("diagnostics").get(0).asString()).contains("start");
        assertThat(failed.path("rolledBack").asBoolean()).as(failed.toString()).isTrue();
        assertThat(failed.path("rollbackVersion").asString()).isEqualTo("1.0.0");
        assertThat(uploads.impact(library).path("plugin").path("generation").asLong()).isGreaterThan(libraryGeneration);
        assertThat(uploads.impact(client).path("plugin").path("generation").asLong()).isGreaterThan(clientGeneration);
        assertThat(uploads.probeVersion(library, 200)).isEqualTo("1.0.0");
        assertThat(uploads.probeVersion(client, 200)).isEqualTo("1.0.0");

        var upgrade = uploads.signed(client, "2.0.0", replacement, 200);
        assertThat(upgrade.path("outcome").asString()).isEqualTo("UPGRADED");
        assertThat(upgrade.path("activated").asBoolean()).isTrue();
        assertThat(uploads.probeVersion(client, 200)).isEqualTo("2.0.0");
        uploads.probeVersion(library, 404);
    }

    private static void replaceInDependencyOrder(ConfigurableApplicationContext app, Uploads uploads) throws Exception {
        for (String order : List.of("base,child", "child,base")) {
            uploads.signed("base", "1.0.0", "", 200);
            uploads.signed("child", "1.0.0", requires("base"), 200);
            if (order.startsWith("base")) {
                uploads.signed("outside", "1.0.0", requires("base"), 200);
                var blocked = uploads.signed("bundle", "2.0.0", "pixiv.replaces=" + order, 409);
                assertThat(blocked.path("outcome").asString()).isEqualTo("REJECTED_DEPENDENCY");
                assertThat(entry(app, "child").runtimePhase().name()).isEqualTo("STARTED");
                // 磁盘描述符已不依赖 base，驻留代次仍会阻止 base 退出。
                uploads.signed("outside", "1.1.0", requires("desktop"), 200);
                long childGeneration = entry(app, "child").generation();
                var liveBlock = uploads.signed("bundle", "2.0.0", "pixiv.replaces=" + order, 409);
                assertThat(liveBlock.path("dependencyProblems").get(0).path("status").asString()).isEqualTo("outside");
                assertThat(entry(app, "child").generation()).isEqualTo(childGeneration);
                app.getBean(PluginManagementService.class).revokeTrust("outside");
                uploads.action("outside", "remove", 200);
            }
            assertThat(uploads.signed("bundle", "2.0.0", "pixiv.replaces=" + order, 200).path("activated").asBoolean()).isTrue();
            assertThat(app.getBean(PluginRuntimeManager.class).status().orElseThrow().startedPluginIds())
                    .contains("bundle").doesNotContain("base", "child");
            uploads.action("bundle", "remove", 200);
        }
    }

    private static void acceptLoadedPendingProvider(ConfigurableApplicationContext app, Uploads uploads) throws Exception {
        uploads.stored("load-desktop", "1.0.0", DESKTOP, false);
        String dependencies = requires("load-desktop@1.0");
        uploads.deferred("before-load", "1.0.0", RESTART + "\n" + dependencies);
        uploads.action("load-desktop", "load", 200);
        var loaded = entry(app, "load-desktop");
        assertThat(loaded.runtimePhase().name()).isEqualTo("LOADED");
        assertThat(loaded.effectiveAfterRestart()).isTrue();
        var toggles = app.getBean(PluginToggleProperties.class);
        toggles.setEnabled("load-desktop", false);
        var disabled = uploads.post("after-load", "1.0.0", RESTART + "\n" + dependencies, 409);
        assertThat(disabled.path("dependencyProblems").get(0).path("status").asString()).isEqualTo("DISABLED");
        toggles.setEnabled("load-desktop", true);
        var management = app.getBean(PluginManagementService.class);
        management.revokeTrust("load-desktop");
        var revoked = uploads.post("after-load", "1.0.0", RESTART + "\n" + dependencies, 409);
        assertThat(revoked.path("outcome").asString()).isEqualTo("REJECTED_DEPENDENCY");
        management.approveTrust("load-desktop", loaded.trust().artifactSha256());
        uploads.deferred("after-load", "1.0.0", RESTART + "\n" + dependencies);
        uploads.deferred("load-only-hot", "1.0.0", dependencies);
        uploads.action("load-only-hot", "load", 409);
        var mismatch = uploads.post("wrong-major", "1.0.0", RESTART + "\n" + requires("load-desktop@2.0"), 409);
        assertThat(mismatch.path("dependencyProblems").get(0).path("reason").asString()).isEqualTo("VERSION_UNSATISFIED");
    }

    private static void reloadWithChangedDependencies(Uploads uploads) throws Exception {
        String client = "third-client", oldProvider = "third-old";
        uploads.post(oldProvider, "1.0.0", "", 200);
        uploads.post(client, "1.0.0", requires(oldProvider), 200);
        uploads.deferred(client, "1.1.0", requires("desktop"));

        var rejected = uploads.action(client, "reload", 409);
        assertThat(rejected.path("code").asString()).isEqualTo("DEPENDENCY_UNSATISFIED");
        assertThat(uploads.probeVersion(client, 200)).isEqualTo("1.0.0");

        uploads.action("desktop", "load", 200);
        uploads.action("desktop", "start", 200);
        uploads.action(client, "stop", 200);
        uploads.action(oldProvider, "stop", 200);
        assertThat(uploads.impact(oldProvider).path("plugin").path("status").asString()).isEqualTo("INSTALLED");
        assertThat(uploads.impact("desktop").path("plugin").path("runtimePhase").asString()).isEqualTo("STARTED");
        uploads.action(client, "reload", 200);
        assertThat(uploads.probeVersion(client, 200)).isEqualTo("1.1.0");
    }

    private static void checkReplacementAndReload(ConfigurableApplicationContext app, Uploads uploads) throws Exception {
        assertThat(entry(app, "desktop").runtimePhase()).isNull();
        var core = uploads.post("needs-core", "1.0.0", requires("core,desktop"), 409);
        assertThat(core.path("outcome").asString()).isEqualTo("REJECTED_DEPENDENCY");
        var problem = core.path("dependencyProblems").get(0);
        assertThat(problem.path("pluginId").asString()).isEqualTo("core");
        assertThat(problem.path("status").asString()).isEqualTo("BUILT_IN");
        assertThat(app.getBean(ExternalPluginInstaller.class).listInstalled()).noneMatch(p -> p.id().equals("needs-core"));
        uploads.deferred("desktop-control", "1.0.0", requires("desktop"));

        trustTestPublisher(app);
        assertThat(uploads.signed("original", "1.0.0", "", 200).path("activated").asBoolean()).isTrue();
        assertThat(uploads.signed("original", "1.1.0", requires("desktop"), 200)
                .path("effectiveAfterRestart").asBoolean()).isTrue();
        for (var id : List.of("reload-bad", "reload-good")) {
            assertThat(uploads.post(id, "1.0.0", "", 200).path("activated").asBoolean()).isTrue();
            uploads.deferred(id, id.equals("reload-bad") ? "7.0.0" : "1.1.0", requires("desktop"));
        }

        var replacement = probe(Path.of("input"), "replacement", "7.0.0", "pixiv.replaces=original");
        var signature = PluginTestProvenance.signArtifact("replacement", "7.0.0", Files.size(replacement),
                PluginPackageIntegrity.sha256Hex(replacement));
        for (boolean startDesktop : List.of(false, true)) {
            if (startDesktop) {
                startColdSkippedDependency(app, uploads);
                uploads.action("desktop-control", "load", 200);
                assertThat(entry(app, "desktop-control").effectiveAfterRestart()).isTrue();
                uploads.action("desktop-control", "start", 200);
                assertThat(entry(app, "desktop-control").effectiveAfterRestart()).isFalse();
            }
            var failed = uploads.upload(replacement, signature, 500);
            assertThat(failed.path("outcome").asString()).isEqualTo("FAILED");
            assertThat(failed.path("diagnostics").get(0).asString()).contains("start");
            assertThat(failed.path("rolledBack").asBoolean()).as(failed.toString()).isTrue();
            assertThat(failed.path("rollbackVersion").asString()).isEqualTo("1.0.0");
            assertServingOldVersion(app, "original", "1.1.0");
            assertThat(app.getBean(ExternalPluginInstaller.class).listInstalled()).noneMatch(p -> p.id().equals("replacement"));
        }
        var restored = uploads.action("reload-bad", "reload", 500);
        assertRollbackResponse(restored, true, "STARTED");
        assertServingOldVersion(app, "reload-bad", "7.0.0");
        assertFailedProviderRejected(uploads, "reload-bad", "7.0");
        System.setProperty("test.plugin.fail-start", "reload-bad@1.0.0");
        try {
            var failed = uploads.action("reload-bad", "reload", 500);
            assertRollbackResponse(failed, false, entry(app, "reload-bad").runtimePhase().name());
        } finally {
            System.clearProperty("test.plugin.fail-start");
        }
        uploads.action("reload-bad", "start", 200);
        assertServingOldVersion(app, "reload-bad", "7.0.0");
        assertFailedProviderRejected(uploads, "reload-bad", "7.0");
        checkDeferredStartRetry(app, uploads);
        uploads.action("reload-good", "reload", 200);
        assertThat(app.getBean(PluginRuntimeManager.class).loadedDescriptor("reload-good").orElseThrow().version()).isEqualTo("1.1.0");
        assertThat(entry(app, "reload-good").runtimePhase().name()).isEqualTo("STARTED");
        assertThat(entry(app, "reload-good").effectiveAfterRestart()).isFalse();
    }

    private static void startColdSkippedDependency(ConfigurableApplicationContext app, Uploads uploads) throws Exception {
        String id = "cold-retry", dependency = requires("desktop@1.0");
        Path jar = probe(Path.of("input"), id, "1.0.0", RESTART + "\n" + dependency);
        var signature = PluginTestProvenance.signArtifact(id, "1.0.0", Files.size(jar), PluginPackageIntegrity.sha256Hex(jar));
        assertThat(uploads.upload(jar, signature, 200).path("outcome").asString()).isEqualTo("INSTALLED");
        assertThat(uploads.upload(jar, signature, 200).path("outcome").asString()).isEqualTo("DUPLICATE");

        uploads.action("desktop", "load", 200);
        assertThat(entry(app, "desktop").runtimePhase().name()).isEqualTo("LOADED");
        var duplicate = uploads.upload(jar, signature, 200);
        assertThat(duplicate.path("outcome").asString()).isEqualTo("DUPLICATE");
        assertThat(duplicate.path("effectiveAfterRestart").asBoolean()).isTrue();
        assertThat(duplicate.path("activated").asBoolean()).isFalse();
        uploads.deferred("cold-new", "1.0.0", dependency);
        assertThat(uploads.action("cold-new", "load", 409).path("code").asString()).isEqualTo("DEPENDENCY_UNSATISFIED");

        var toggles = app.getBean(PluginToggleProperties.class);
        toggles.setEnabled("desktop", false);
        assertThat(uploads.upload(jar, signature, 409).path("dependencyProblems").get(0).path("status").asString())
                .isEqualTo("DISABLED");
        toggles.setEnabled("desktop", true);
        var management = app.getBean(PluginManagementService.class);
        String digest = entry(app, "desktop").trust().artifactSha256();
        management.revokeTrust("desktop");
        assertThat(uploads.upload(jar, signature, 409).path("outcome").asString()).isEqualTo("REJECTED_DEPENDENCY");
        management.approveTrust("desktop", digest);

        System.setProperty("test.plugin.fail-start", "desktop@1.0.0");
        try {
            uploads.action("desktop", "start", 500);
            assertThat(uploads.upload(jar, signature, 409).path("dependencyProblems").get(0).path("status").asString())
                    .isEqualTo("START_FAILED");
        } finally {
            System.clearProperty("test.plugin.fail-start");
        }
        uploads.action("desktop", "start", 200);
        var afterStart = uploads.upload(jar, signature, 200);
        assertThat(afterStart.path("outcome").asString()).isEqualTo("DUPLICATE");
        assertThat(afterStart.path("effectiveAfterRestart").asBoolean()).isTrue();
        uploads.action("cold-new", "load", 200);
        uploads.action("cold-new", "start", 200);
        assertThat(uploads.probeVersion("cold-new", 200)).isEqualTo("1.0.0");
    }

    private static void assertRollbackResponse(JsonNode response, boolean restored, String phase) {
        assertThat(response.path("code").asString()).isEqualTo(restored ? "RELOAD_ROLLED_BACK" : "RELOAD_ROLLBACK_FAILED");
        assertThat(response.path("rolledBack").isBoolean()).isTrue();
        assertThat(response.path("rolledBack").asBoolean()).isEqualTo(restored);
        assertThat(response.path("loadedVersion").asString()).isEqualTo("1.0.0");
        assertThat(response.path("runtimePhase").asString()).isEqualTo(phase);
        assertThat(response.path("error").asString()).isNotBlank().doesNotContain("fixture", "Exception");
    }

    private static void assertFailedProviderRejected(Uploads uploads, String id, String version) throws Exception {
        var rejected = uploads.post("rejected-client-" + id, "1.0.0", requires(id + "@" + version), 409);
        assertThat(rejected.path("outcome").asString()).isEqualTo("REJECTED_DEPENDENCY");
        var problem = rejected.path("dependencyProblems").get(0);
        assertThat(problem.path("pluginId").asString()).isEqualTo(id);
        assertThat(problem.path("reason").asString()).isEqualTo("UNAVAILABLE");
        assertThat(problem.path("status").asString()).isEqualTo("START_FAILED");
    }

    private static void checkDeferredStartRetry(ConfigurableApplicationContext app, Uploads uploads) throws Exception {
        uploads.stored("bad-desktop", "7.0.0", DESKTOP, false);
        uploads.action("bad-desktop", "load", 200);
        uploads.action("bad-desktop", "start", 500);
        assertThat(entry(app, "bad-desktop").runtimePhase().name()).isEqualTo("LOADED");
        assertThat(entry(app, "bad-desktop").effectiveAfterRestart()).isFalse();
        assertFailedProviderRejected(uploads, "bad-desktop", "7.0");
        uploads.deferred("bad-desktop", "8.0.0", DESKTOP + "\n" + RESTART);
        uploads.deferred("repaired-client", "1.0.0", requires("bad-desktop@8.0"));

        uploads.stored("retry-desktop", "1.0.0", DESKTOP, false);
        uploads.action("retry-desktop", "load", 200);
        System.setProperty("test.plugin.fail-start", "retry-desktop@1.0.0");
        try {
            uploads.action("retry-desktop", "start", 500);
            assertFailedProviderRejected(uploads, "retry-desktop", "1.0");
        } finally {
            System.clearProperty("test.plugin.fail-start");
        }
        uploads.action("retry-desktop", "unload", 200);
        System.setProperty("test.plugin.fail-init", "retry-desktop@1.0.0");
        try {
            assertThat(uploads.action("retry-desktop", "load", 500).path("code").asString()).isEqualTo("INITIALIZATION_FAILED");
        } finally {
            System.clearProperty("test.plugin.fail-init");
        }
        uploads.action("retry-desktop", "load", 200);
        assertFailedProviderRejected(uploads, "retry-desktop", "1.0");
        uploads.action("retry-desktop", "start", 200);
        assertThat(entry(app, "retry-desktop").effectiveAfterRestart()).isFalse();
        assertThat(uploads.post("retry-client", "1.0.0", requires("retry-desktop@1.0"), 200)
                .path("activated").asBoolean()).isTrue();
    }

    private static void assertServingOldVersion(ConfigurableApplicationContext app, String id, String diskVersion) {
        var state = entry(app, id);
        assertThat(state.version()).isEqualTo(diskVersion);
        assertThat(state.loadedVersion()).isEqualTo("1.0.0");
        assertThat(state.runtimePhase().name()).isEqualTo("STARTED");
        assertThat(state.effectiveAfterRestart()).isTrue();
        assertThat(app.getBean(PluginRuntimeManager.class).loadedDescriptor(id).orElseThrow().version()).isEqualTo("1.0.0");
        assertThat(app.getBean(PluginRegistry.class).registeredPlugins()).anyMatch(p -> p.id().equals(id));
    }

    private static PluginManagementService.PluginManagementEntry entry(ConfigurableApplicationContext app, String id) {
        return app.getBean(PluginManagementService.class).list().plugins().stream()
                .filter(p -> id.equals(p.id())).findFirst().orElseThrow();
    }

    private record Uploads(HttpClient client, int port, String session) {
        JsonNode post(String id, String version, String properties, int status) throws Exception {
            return upload(probe(Path.of("input"), id, version, properties), null, status);
        }

        JsonNode signed(String id, String version, String properties, int status) throws Exception {
            var jar = probe(Path.of("input"), id, version, properties);
            return upload(jar, PluginTestProvenance.signArtifact(id, version, Files.size(jar),
                    PluginPackageIntegrity.sha256Hex(jar)), status);
        }

        JsonNode upload(Path jar, SignatureMetadata signature, int status) throws Exception {
            return upload(jar, signature, status, true);
        }

        JsonNode upload(Path jar, SignatureMetadata signature, int status, boolean confirmTrust) throws Exception {
            var boundary = UUID.randomUUID().toString();
            var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString("--" + boundary
                            + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"upload.jar\"\r\n"
                            + "Content-Type: application/java-archive\r\n\r\n"),
                    HttpRequest.BodyPublishers.ofFile(jar),
                    HttpRequest.BodyPublishers.ofString(signature == null ? "" : "\r\n--" + boundary
                            + "\r\nContent-Disposition: form-data; name=\"signature\"; filename=\"signature.json\"\r\n"
                            + "Content-Type: application/json\r\n\r\n" + JsonMapper.shared().writeValueAsString(signature)),
                    HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
            var base = "http://127.0.0.1:" + port;
            String confirmation = confirmTrust ? "?confirmTrust=" + PluginPackageIntegrity.sha256Hex(jar) : "";
            var request = HttpRequest.newBuilder(URI.create(base + "/api/plugins/install" + confirmation)).timeout(Duration.ofSeconds(60))
                    .header("Origin", base).header("X-Session-Token", session)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(body).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
            return JsonMapper.shared().readTree(response.body());
        }

        JsonNode impact(String id) throws Exception {
            return getManagement(id + "/impact");
        }

        JsonNode status() throws Exception {
            return getManagement("status");
        }

        private JsonNode getManagement(String path) throws Exception {
            return getJson("/api/plugins/" + path, "en-US", 200);
        }

        private JsonNode getJson(String path, String language, int expectedStatus) throws Exception {
            var uri = URI.create("http://127.0.0.1:" + port + path);
            var preview = client.send(HttpRequest.newBuilder(uri).header("X-Session-Token", session)
                    .header("Accept-Language", language).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(preview.statusCode()).as(preview.body()).isEqualTo(expectedStatus);
            return JsonMapper.shared().readTree(preview.body());
        }

        JsonNode writeJson(String method, String path, Object body, int status) throws Exception {
            var base = "http://127.0.0.1:" + port;
            var request = HttpRequest.newBuilder(URI.create(base + path)).header("Origin", base)
                    .header("X-Session-Token", session).header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(JsonMapper.shared().writeValueAsString(body))).build();
            var result = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(result.statusCode()).as(result.body()).isEqualTo(status);
            return JsonMapper.shared().readTree(result.body());
        }

        JsonNode action(String id, String verb, int status) throws Exception {
            var fingerprint = impact(id).path("fingerprint").asString();
            var base = "http://127.0.0.1:" + port;
            var uri = base + "/api/plugins/" + id;
            var request = HttpRequest.newBuilder(URI.create(uri + "/actions/" + verb)).header("Origin", base)
                    .header("X-Session-Token", session).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonMapper.shared().writeValueAsString(
                            java.util.Map.of("fingerprint", fingerprint)))).build();
            var result = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(result.statusCode()).as(result.body()).isEqualTo(status);
            return JsonMapper.shared().readTree(result.body());
        }

        String probeVersion(String id, int status) throws Exception {
            var uri = URI.create("http://127.0.0.1:" + port + "/repair-probe/" + id + "/version.txt");
            var reply = client.send(HttpRequest.newBuilder(uri).header("X-Session-Token", session).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(reply.statusCode()).as(reply.body()).isEqualTo(status);
            if (status == 302) assertThat(reply.headers().firstValue("Location").orElseThrow()).endsWith("/plugin-market.html");
            if (status == 503) assertThat(reply.headers().firstValue("Retry-After")).contains("30");
            return reply.body();
        }

        void deferred(String id, String version, String properties) throws Exception {
            stored(id, version, properties, true);
        }

        void stored(String id, String version, String properties, boolean pendingRestart) throws Exception {
            var result = post(id, version, properties, 200);
            assertThat(result.path("effectiveAfterRestart").asBoolean()).as(result.toString()).isEqualTo(pendingRestart);
            assertThat(result.path("activated").asBoolean()).isFalse();
            assertThat(result.path("dependencyProblems").size()).isZero();
        }
    }
}
