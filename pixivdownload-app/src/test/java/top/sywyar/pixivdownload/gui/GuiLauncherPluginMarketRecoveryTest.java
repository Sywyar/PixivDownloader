package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import static org.mockito.Mockito.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("无桌面界面插件时的插件市场恢复入口")
class GuiLauncherPluginMarketRecoveryTest {

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
