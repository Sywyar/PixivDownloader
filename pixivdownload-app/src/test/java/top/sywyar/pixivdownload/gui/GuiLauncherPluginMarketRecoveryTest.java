package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("无桌面界面插件时的插件市场恢复入口")
class GuiLauncherPluginMarketRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("按本机服务配置生成插件市场地址并拒绝非法域名")
    void buildsLocalPluginMarketUriFromServerConfiguration() throws Exception {
        assertThat(GuiLauncher.pluginMarketUri(null, 6999).toString())
                .isEqualTo("http://localhost:6999/plugin-market.html");

        Path httpsConfig = tempDir.resolve("https.yaml");
        Files.writeString(httpsConfig, "server.ssl.enabled: true\nssl.domain: app.example.test\n");
        assertThat(GuiLauncher.pluginMarketUri(httpsConfig, 7443).toString())
                .isEqualTo("https://app.example.test:7443/plugin-market.html");

        Path invalidDomain = tempDir.resolve("invalid-domain.yaml");
        Files.writeString(invalidDomain, "ssl.domain: https://example.test/path\n");
        assertThat(GuiLauncher.pluginMarketUri(invalidDomain, 6999).toString())
                .isEqualTo("http://localhost:6999/plugin-market.html");
    }
}
