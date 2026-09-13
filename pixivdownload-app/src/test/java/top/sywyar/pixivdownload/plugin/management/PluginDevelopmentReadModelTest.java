package top.sywyar.pixivdownload.plugin.management;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ResourceLock("java.lang.System.properties")
class PluginDevelopmentReadModelTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @CsvSource({"false,false,true", "true,false,false", "true,true,true"})
    @DisplayName("状态和 i18n 查询只在正式运行或独立 SDK 工程中扫描已安装包")
    void readModelsFollowRuntimeSource(boolean development, boolean standalone, boolean scanInstalled)
            throws Exception {
        String oldEnabled = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        String oldRoot = System.getProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);
        try {
            System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, Boolean.toString(development));
            System.setProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, root.toString());
            if (standalone) {
                Path descriptor = root.resolve("src/main/resources/plugin.properties");
                Files.createDirectories(descriptor.getParent());
                Files.writeString(descriptor, "plugin.id=source", StandardCharsets.UTF_8);
            }
            ExternalPluginInstaller installer = mock(ExternalPluginInstaller.class);
            when(installer.pluginsDirectory()).thenReturn(root.resolve("plugins"));
            when(installer.recoveryGateSnapshot()).thenReturn(
                    PluginRecoveryGateSnapshot.safe(PluginTransactionRecoveryReport.success()));
            PluginDescriptor descriptor = new PluginDescriptor("installed", "installed", "1.2.3",
                    VersionRequirement.unspecified(), List.of(), "example.Plugin", "installed",
                    "plugin.name", "plugin.description", null, null, PluginKind.FEATURE);
            when(installer.listInstalled()).thenReturn(List.of(
                    new InstalledPlugin(descriptor, root.resolve("plugins/installed.jar"))));
            when(installer.snapshotInstalledWithProvenance(anyInt(), anyLong()))
                    .thenReturn(new InstalledPluginInventorySnapshot(List.of(), false));
            PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
            when(runtime.inspectPlugins()).thenReturn(new PluginInventory(List.of(), List.of()));
            PluginRegistry plugins = new PluginRegistry(List.of());
            RequiredPluginPolicy policy = RequiredPluginPolicy.empty();
            PluginStatusService status = new PluginStatusService(plugins, runtime, installer, policy);
            PluginManagementService management = new PluginManagementService(status,
                    mock(PluginLifecycleService.class), policy, mock(RecoveryModeService.class),
                    null, installer, new PluginToggleProperties());
            StaticListableBeanFactory beans = new StaticListableBeanFactory();
            beans.addBean("installer", installer);
            WebI18nBundleRegistry i18n = new WebI18nBundleRegistry(
                    plugins, beans.getBeanProvider(ExternalPluginInstaller.class));
            for (int i = 0; i < 5; i++) {
                assertThat(status.report().byId("installed").isPresent()).isEqualTo(scanInstalled);
                assertThat(management.list().plugins()).hasSize(scanInstalled ? 1 : 0);
                assertThat(i18n.supportedNamespaces()).hasSize(scanInstalled ? 1 : 0);
            }
            verify(installer, scanInstalled ? atLeastOnce() : never()).listInstalled();
            verify(installer, scanInstalled ? atLeastOnce() : never())
                    .snapshotInstalledWithProvenance(anyInt(), anyLong());
            clearInvocations(installer);
            for (int i = 0; i < 5; i++) {
                i18n.unregister("unrelated");
            }
            verifyNoInteractions(installer);
        } finally {
            if (oldEnabled == null) System.clearProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
            else System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, oldEnabled);
            if (oldRoot == null) System.clearProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);
            else System.setProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, oldRoot);
        }
    }
}
