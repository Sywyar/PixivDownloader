package top.sywyar.pixivdownload.plugin.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.bootstrapprobe.BackendRestartProbeFeaturePlugin;
import top.sywyar.pixivdownload.bootstrapprobe.BackendRestartProbePlugin;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyResolver;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
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

@DisplayName("进程重启插件的真实文件事务与冻结运行实例")
class ProcessRestartRemovalIntegrationTest {
    @TempDir
    Path home;

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
        Path file = home.resolve(id + "-" + version + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("plugin.properties"));
            zip.write(("plugin.id=" + id + "\nplugin.version=" + version + "\nplugin.requires="
                    + SdkVersion.MAJOR + "." + SdkVersion.MINOR + "\n"
                    + "pixiv.lifecycle-policy=process-restart\npixiv.execution-mode=host-process-full-trust\n"
                    + "plugin.class=" + BackendRestartProbePlugin.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
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
