package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProxyPacController 单元测试")
class ProxyPacControllerTest {

    private ProxyConfig proxyConfig;
    private ProxyPacController controller;

    @BeforeEach
    void setUp() {
        proxyConfig = new ProxyConfig();
        controller = new ProxyPacController(proxyConfig);
    }

    @Test
    @DisplayName("代理启用时 Pixiv 域名应走配置的 host:port，其余直连")
    void shouldRoutePixivDomainsThroughConfiguredProxyWhenEnabled() {
        proxyConfig.setEnabled(true);
        proxyConfig.setHost("127.0.0.1");
        proxyConfig.setPort(7890);

        ResponseEntity<String> resp = controller.proxyPac();
        String body = resp.getBody();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body).contains("function FindProxyForURL(url, host)");
        assertThat(body).contains("*.pixiv.net");
        assertThat(body).contains("*.pximg.net");
        assertThat(body).contains("return \"PROXY 127.0.0.1:7890; DIRECT\";");
        assertThat(body).contains("return \"DIRECT\";");
    }

    @Test
    @DisplayName("PAC 响应应为 PAC MIME 类型且禁用缓存")
    void shouldServePacMimeTypeAndNoStore() {
        proxyConfig.setEnabled(true);
        proxyConfig.setHost("127.0.0.1");
        proxyConfig.setPort(7890);

        ResponseEntity<String> resp = controller.proxyPac();

        assertThat(resp.getHeaders().getContentType().toString())
                .startsWith("application/x-ns-proxy-autoconfig");
        assertThat(resp.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    @DisplayName("代理禁用时 PAC 对所有域名返回直连")
    void shouldReturnDirectForAllWhenProxyDisabled() {
        proxyConfig.setEnabled(false);
        proxyConfig.setHost("127.0.0.1");
        proxyConfig.setPort(7890);

        String body = controller.proxyPac().getBody();

        assertThat(body).doesNotContain("PROXY");
        // Pixiv 分支命中后也只返回 DIRECT，与兜底分支一致
        assertThat(body).contains("return \"DIRECT\";");
    }

    @Test
    @DisplayName("host 为空或端口非法时回退为全直连")
    void shouldFallBackToDirectWhenHostOrPortInvalid() {
        proxyConfig.setEnabled(true);
        proxyConfig.setHost("   ");
        proxyConfig.setPort(0);

        String body = controller.proxyPac().getBody();

        assertThat(body).doesNotContain("PROXY");
    }

    @Test
    @DisplayName("host 含双引号 / 反斜杠时应转义，避免破坏 PAC 脚本")
    void shouldEscapeSpecialCharactersInHost() {
        proxyConfig.setEnabled(true);
        proxyConfig.setHost("evil\"host\\x");
        proxyConfig.setPort(7890);

        String body = controller.proxyPac().getBody();

        assertThat(body).contains("PROXY evil\\\"host\\\\x:7890; DIRECT");
    }
}
