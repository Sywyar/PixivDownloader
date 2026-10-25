package top.sywyar.pixivdownload.plugin.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.bootstrapprobe.BackendRestartProbeFeaturePlugin;
import top.sywyar.pixivdownload.bootstrapprobe.BackendRestartProbePlugin;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyResolver;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.sdk.SdkVersion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("进程重启插件的真实文件事务与冻结运行实例")
class ProcessRestartRemovalIntegrationTest {
    @TempDir
    Path home;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"hot-reload", "backend-restart", "process-restart"})
    @DisplayName("多模块开发安装真实包按生命周期激活，重启后继续校验并加载")
    void developmentStorageAndRestartMatchLoadingContract(String policy) throws Exception {
        var enabled = top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts.ENABLED_PROPERTY;
        var root = top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts.ROOT_PROPERTY;
        String oldEnabled = System.getProperty(enabled), oldRoot = System.getProperty(root);
        System.setProperty(enabled, "true");
        System.setProperty(root, home.toString());
        String id = new BackendRestartProbeFeaturePlugin().id();
        Path plugins = home.resolve("plugins");
        try {
            Path source = packageFile(id, "7.2.0", policy);
            try (var session = PluginBootstrapSession.createContext(plugins, PluginEnabledSnapshot.empty()).start()) {
                var installer = session.installer();
                var runtime = session.manager();
                var footprint = mock(PluginLifecycleService.class);
                when(footprint.phase(id)).thenAnswer(ignored -> runtime.packagePhases().containsKey(id)
                        ? java.util.Optional.of(PluginRuntimePhase.valueOf(runtime.packagePhases().get(id).name()))
                        : java.util.Optional.empty());
                var coordinator = new ExternalPluginLifecycleCoordinator(runtime, footprint, installer,
                        mock(RecoveryModeService.class), new PluginDependencyResolver(installer));
                var result = coordinator.installOrUpdate(source, false,
                        PluginPackageOrigin.localUnsignedUpload(PluginPackageIntegrity.sha256Hex(source)));
                assertThat(result.installResult().accepted()).as("%s", result).isTrue();
                assertThat(result.activated()).isEqualTo(!"process-restart".equals(policy));
                assertThat(installer.listInstalled()).hasSize(1);
                assertThat(PluginPackageIntegrity.sha256Hex(installer.listInstalled().get(0).path()))
                        .isEqualTo(PluginPackageIntegrity.sha256Hex(source));
                assertThat(runtime.loadedDescriptor(id).isPresent()).isEqualTo(!"process-restart".equals(policy));
            }
            try (var session = PluginBootstrapSession.createContext(plugins, PluginEnabledSnapshot.empty()).start()) {
                assertThat(session.manager().loadedDescriptor(id)).isPresent();
                assertThat(session.status().failures()).isEmpty();
            }
            System.setProperty(enabled, "false");
            try (var session = PluginBootstrapSession.createContext(plugins, PluginEnabledSnapshot.empty()).start()) {
                assertThat(session.manager().loadedDescriptor(id).orElseThrow().version()).isEqualTo("7.2.0");
            }
        } finally {
            if (oldEnabled == null) System.clearProperty(enabled); else System.setProperty(enabled, oldEnabled);
            if (oldRoot == null) System.clearProperty(root); else System.setProperty(root, oldRoot);
        }
    }

    @Test
    @DisplayName("安装同名包保留调试源码的代次，重启后按来源冲突拒绝加载")
    void installationPreservesSourceGenerationAndRestartReportsConflict() throws Exception {
        var enabled = top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts.ENABLED_PROPERTY;
        var root = top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts.ROOT_PROPERTY;
        String oldEnabled = System.getProperty(enabled), oldRoot = System.getProperty(root);
        System.setProperty(enabled, "true");
        System.setProperty(root, home.toString());
        try {
            String id = new BackendRestartProbeFeaturePlugin().id();
            Path plugins = home.resolve("plugins");
            Path source = packageFile(id, "7.2.0", "hot-reload");
            Path module = home.resolve("source-module");
            Path classes = module.resolve("target/classes");
            try (var zip = new java.util.zip.ZipFile(source.toFile())) {
                for (var entry : java.util.Collections.list(zip.entries())) {
                    Path target = classes.resolve(entry.getName());
                    Files.createDirectories(target.getParent());
                    try (var input = zip.getInputStream(entry)) { Files.copy(input, target); }
                }
            }
            Path descriptor = module.resolve("src/main/resources/plugin.properties");
            Files.createDirectories(descriptor.getParent());
            Files.copy(classes.resolve("plugin.properties"), descriptor);
            try (var installer = new ExternalPluginInstaller(plugins)) {
                installer.recoverPendingTransactions();
                var runtime = new PluginRuntimeManager(plugins);
                try {
                    assertThat(runtime.start().failures()).isEmpty();
                    long generation = runtime.generation(id).orElseThrow();
                    var result = coordinator(runtime, installer).installOrUpdate(source, false,
                            PluginPackageOrigin.localUnsignedUpload(PluginPackageIntegrity.sha256Hex(source)));
                    assertThat(result.installResult().accepted()).isTrue();
                    assertThat(result.activated()).isFalse();
                    assertThat(runtime.isDevelopmentArtifact(id)).isTrue();
                    assertThat(runtime.generation(id)).contains(generation);
                    assertThat(runtime.artifactPath(id)).contains(classes.toAbsolutePath());
                } finally { runtime.shutdown(); }
                var restarted = new PluginRuntimeManager(plugins);
                try {
                    var status = restarted.start();
                    assertThat(status.loadedPluginIds()).isEmpty();
                    assertThat(status.failures()).hasSize(2)
                            .allSatisfy(failure -> assertThat(failure.phase()).isEqualTo("plugin-conflict"));
                } finally { restarted.shutdown(); }
            }
        } finally {
            if (oldEnabled == null) System.clearProperty(enabled); else System.setProperty(enabled, oldEnabled);
            if (oldRoot == null) System.clearProperty(root); else System.setProperty(root, oldRoot);
        }
    }

    @Test
    @DisplayName("升级和移除不触碰驻留实例及用户文件，关闭后新运行时不再加载，重新安装后恢复加载")
    void removeRetainsFrozenRuntimeAndUserFilesUntilRestart() throws Exception {
        String id = new BackendRestartProbeFeaturePlugin().id();
        Path plugins = home.resolve("plugins");
        List<Path> retained = List.of(home.resolve("config/plugins/" + id + ".properties"),
                home.resolve("data/" + id + "/plugin.db"), home.resolve("state/tasks.json"),
                home.resolve("media/example.txt"));
        for (Path file : retained) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "retained-user-content", StandardCharsets.UTF_8);
        }
        Path oldPackage = packageFile(id, "7.2.0");
        Path newPackage = packageFile(id, "7.3.0");
        try (ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins)) {
            installer.recoverPendingTransactions();
            PluginRuntimeManager runtime = new PluginRuntimeManager(plugins);
            try {
                ExternalPluginLifecycleCoordinator coordinator = coordinator(runtime, installer);
                var firstInstall = coordinator.installOrUpdate(oldPackage, false,
                        PluginPackageOrigin.localUnsignedUpload(PluginPackageIntegrity.sha256Hex(oldPackage)));
                assertThat(firstInstall.installResult().accepted()).as("%s", firstInstall).isTrue();
                runtime.start();
                var installation = runtime.inspectPlugins().installations().get(0);
                assertThat(installation.id()).isEqualTo(id);
                assertThat(runtime.loadedDescriptor(id).orElseThrow().version()).isEqualTo("7.2.0");
                assertThat(coordinator.installOrUpdate(newPackage, false,
                        PluginPackageOrigin.localUnsignedUpload(PluginPackageIntegrity.sha256Hex(newPackage)))
                        .installResult().accepted()).isTrue();
                Path installedArtifact = installer.listInstalled().get(0).path();
                Path sidecar = new PluginProvenanceStore(plugins).sidecarPath(installedArtifact);
                assertThat(sidecar).exists();
                var footprint = mock(PluginLifecycleService.class);
                when(footprint.phase(id)).thenReturn(java.util.Optional.of(PluginRuntimePhase.STARTED));
                var duplicate = new ExternalPluginLifecycleCoordinator(runtime, footprint, installer,
                        mock(RecoveryModeService.class), new PluginDependencyResolver(installer))
                        .installOrUpdate(newPackage, false,
                                PluginPackageOrigin.localUnsignedUpload(PluginPackageIntegrity.sha256Hex(newPackage)));
                assertThat(duplicate.installResult().outcome())
                        .isEqualTo(top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome.DUPLICATE);
                assertThat(duplicate.activated()).isFalse();

                assertThat(coordinator.remove(id)).isTrue();

                assertThat(installer.listInstalled()).isEmpty();
                assertThat(installedArtifact).doesNotExist();
                assertThat(sidecar).doesNotExist();
                assertThat(runtime.inspectPlugins().installations().get(0).plugin()).isSameAs(installation.plugin());
                assertThat(installation.plugin().id()).isEqualTo(id);
                try (var resource = installation.classLoader().getResourceAsStream("plugin.properties")) {
                    assertThat(resource).isNotNull();
                    assertThat(new String(resource.readAllBytes(), StandardCharsets.UTF_8)).contains("plugin.version=7.2.0");
                }
            } finally {
                runtime.shutdown();
            }
            PluginRuntimeManager restarted = new PluginRuntimeManager(plugins);
            try {
                restarted.start();
                assertThat(restarted.loadedDescriptors()).isEmpty();
                assertThat(coordinator(restarted, installer).installOrUpdate(newPackage, false,
                        PluginPackageOrigin.localUnsignedUpload(PluginPackageIntegrity.sha256Hex(newPackage)))
                        .installResult().accepted()).isTrue();
                assertThat(restarted.loadedDescriptors()).isEmpty();
            } finally {
                restarted.shutdown();
            }
            PluginRuntimeManager reinstalled = new PluginRuntimeManager(plugins);
            try {
                reinstalled.start();
                assertThat(reinstalled.loadedDescriptor(id).orElseThrow().version()).isEqualTo("7.3.0");
                var footprint = mock(PluginLifecycleService.class);
                when(footprint.phase(id)).thenReturn(java.util.Optional.of(PluginRuntimePhase.STARTED));
                var active = new ExternalPluginLifecycleCoordinator(reinstalled, footprint, installer,
                        mock(RecoveryModeService.class), new PluginDependencyResolver(installer));
                assertThat(active.installOrUpdate(newPackage, false,
                        PluginPackageOrigin.localUnsignedUpload(PluginPackageIntegrity.sha256Hex(newPackage)))
                        .activated()).isTrue();
                Path different = packageFile(id, "7.3.0", "process-restart", "different bytes");
                assertThat(active.installOrUpdate(different, false,
                        PluginPackageOrigin.localUnsignedUpload(PluginPackageIntegrity.sha256Hex(different)))
                        .installResult().accepted()).isFalse();
                assertThat(active.remove(id)).isTrue();
                assertThat(active.installOrUpdate(different, false,
                        PluginPackageOrigin.localUnsignedUpload(PluginPackageIntegrity.sha256Hex(different)))
                        .installResult().accepted()).isTrue();
                assertThat(active.installOrUpdate(different, false,
                        PluginPackageOrigin.localUnsignedUpload(PluginPackageIntegrity.sha256Hex(different)))
                        .activated()).isFalse();
            } finally {
                reinstalled.shutdown();
            }
        }
        for (Path file : retained) {
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("retained-user-content");
        }
    }

    private ExternalPluginLifecycleCoordinator coordinator(PluginRuntimeManager runtime, ExternalPluginInstaller installer) {
        return new ExternalPluginLifecycleCoordinator(runtime, mock(PluginLifecycleService.class), installer,
                mock(RecoveryModeService.class), new PluginDependencyResolver(installer));
    }

    private Path packageFile(String id, String version) throws Exception {
        return packageFile(id, version, "process-restart");
    }

    private Path packageFile(String id, String version, String policy) throws Exception {
        return packageFile(id, version, policy, "original bytes");
    }

    private Path packageFile(String id, String version, String policy, String payload) throws Exception {
        Path file = home.resolve(id + "-" + version + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("plugin.properties"));
            zip.write(("plugin.id=" + id + "\nplugin.version=" + version + "\nplugin.requires="
                    + SdkVersion.MAJOR + "." + SdkVersion.MINOR + "\n"
                    + "pixiv.lifecycle-policy=" + policy + "\npixiv.execution-mode=host-process-full-trust\n"
                    + "plugin.class=" + BackendRestartProbePlugin.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("payload.txt"));
            zip.write(payload.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Class<?> type : List.of(BackendRestartProbePlugin.class, BackendRestartProbeFeaturePlugin.class)) {
                String entry = type.getName().replace('.', '/') + ".class";
                zip.putNextEntry(new ZipEntry(entry));
                try (var input = type.getResourceAsStream("/" + entry)) {
                    assertThat(input).isNotNull();
                    input.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
        return file;
    }
}
