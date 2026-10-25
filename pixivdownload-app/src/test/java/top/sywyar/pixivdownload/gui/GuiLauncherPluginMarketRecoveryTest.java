package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import static org.mockito.Mockito.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("无桌面界面插件时的插件市场恢复入口")
class GuiLauncherPluginMarketRecoveryTest {

    @Test
    @DisplayName("恢复诊断随当前活动插件变化，不沿用启动时的禁用结论")
    void recoveryUsesCurrentDiscovery() {
        var runtime = mock(PluginRuntimeManager.class);
        var installer = mock(ExternalPluginInstaller.class);
        var session = session(runtime, installer);
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override public String id() { return "recovery-fixture"; }
            @Override public String displayName() { return "fixture"; }
            @Override public String description() { return "fixture"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
        };
        var inventory = new PluginInventory(List.of(new PluginInstallation(
                PluginDescriptor.forBuiltIn(plugin), PluginStatus.STARTED, getClass().getClassLoader(), plugin)),
                List.of());
        when(installer.recoveryGateSnapshot()).thenReturn(
                PluginRecoveryGateSnapshot.safe(PluginTransactionRecoveryReport.success()));
        when(runtime.inspectPlugins()).thenReturn(inventory);
        when(runtime.discoverFeaturePlugins()).thenReturn(PluginDiscoveryResult.empty());
        var failure = new DesktopUiFailure(DesktopUiFailure.Reason.NO_PROVIDER, "no desktop provider");

        assertThat(GuiLauncher.desktopRecoveryGuidance(session, failure).errors())
                .anyMatch(error -> error.startsWith(plugin.id() + " [DISABLED]"));

        when(runtime.discoverFeaturePlugins()).thenReturn(inventory.toDiscoveryResult());
        assertThat(GuiLauncher.desktopRecoveryGuidance(session, failure).errors())
                .noneMatch(error -> error.startsWith(plugin.id() + " ["));
    }

    @Test
    @DisplayName("恢复清点未获准时保留诊断，不读取插件发现或安装目录")
    void recoveryGatePreventsDiscovery() {
        var runtime = mock(PluginRuntimeManager.class);
        var installer = mock(ExternalPluginInstaller.class);
        var session = session(runtime, installer);
        when(installer.recoveryGateSnapshot()).thenReturn(PluginRecoveryGateSnapshot.unchecked());
        when(runtime.status().orElseThrow().failures()).thenReturn(
                List.of(new PluginLoadFailure("fixture.jar", "fixture failure")));

        var guidance = GuiLauncher.desktopRecoveryGuidance(session, null);

        assertThat(guidance.adviceKeys()).contains("recovery.advice.transaction");
        assertThat(guidance.errors()).contains("fixture.jar: fixture failure");
        verify(runtime, never()).discoverFeaturePlugins();
        verify(runtime, never()).inspectPlugins();
        verify(installer, never()).listInstalled();
    }

    private static PluginBootstrapSession session(PluginRuntimeManager runtime, ExternalPluginInstaller installer) {
        var session = mock(PluginBootstrapSession.class);
        when(session.manager()).thenReturn(runtime);
        when(session.installer()).thenReturn(installer);
        when(session.enabledSnapshot()).thenReturn(PluginEnabledSnapshot.empty());
        when(runtime.status()).thenReturn(Optional.of(mock(PluginRuntimeStatus.class)));
        return session;
    }

    @Test
    @DisplayName("按本机服务配置生成插件市场地址并拒绝非法域名")
    void buildsLocalPluginMarketUriFromServerConfiguration() throws Exception {
        var config = mock(DesktopUiHost.ConfigFile.class);
        when(config.readAll(any())).thenReturn(Map.of());
        var host = new AppDesktopUiHost(7443, config);
        assertThat(host.backendUri("/plugin-market.html").toString())
                .isEqualTo("http://localhost:7443/plugin-market.html");
        when(config.readAll(any())).thenReturn(Map.of("server.ssl.enabled", "true", "ssl.domain", "app.example.test"));
        assertThat(host.backendUri("/plugin-market.html").toString())
                .isEqualTo("https://app.example.test:7443/plugin-market.html");
        when(config.readAll(any())).thenReturn(Map.of("server.ssl.enabled", "true", "ssl.domain", "https://example.test/path"));
        assertThat(host.backendUri("/plugin-market.html").toString())
                .isEqualTo("https://localhost:7443/plugin-market.html");
    }
}
