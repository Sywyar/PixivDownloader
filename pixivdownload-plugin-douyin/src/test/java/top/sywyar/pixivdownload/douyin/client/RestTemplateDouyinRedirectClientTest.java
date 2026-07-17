package top.sywyar.pixivdownload.douyin.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.settings.DouyinProxyMode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static top.sywyar.pixivdownload.douyin.HostSettingsFixtures.proxySettings;

@DisplayName("Douyin 重定向客户端代理路由")
class RestTemplateDouyinRedirectClientTest {

    private final AtomicInteger targetHits = new AtomicInteger();
    private final AtomicInteger proxyHits = new AtomicInteger();
    private HttpServer targetServer;
    private HttpServer proxyServer;

    @BeforeEach
    void startServers() throws IOException {
        OutboundProxyOverride.clear();
        targetServer = startServer("target", targetHits);
        proxyServer = startServer("proxy", proxyHits);
    }

    @AfterEach
    void stopServers() {
        OutboundProxyOverride.clear();
        if (targetServer != null) {
            targetServer.stop(0);
        }
        if (proxyServer != null) {
            proxyServer.stop(0);
        }
    }

    @Test
    @DisplayName("显式直连覆盖不会回退到全局或自定义代理")
    void explicitDirectOverrideBypassesConfiguredProxies() throws Exception {
        OutboundProxyOverride.setDirect();

        assertResponse(globalProxyClient().get(targetUri()), "target");
        assertResponse(customProxyClient().get(targetUri()), "target");

        assertThat(targetHits).hasValue(2);
        assertThat(proxyHits).hasValue(0);
    }

    @Test
    @DisplayName("任务代理覆盖也适用于插件直连模式的短链客户端")
    void taskProxyOverrideAppliesToDirectRedirectClient() throws Exception {
        OutboundProxyOverride.set(
                "127.0.0.1:" + proxyServer.getAddress().getPort());

        assertResponse(new RestTemplateDouyinRedirectClient((OutboundProxySettings) null)
                .get(targetUri()), "proxy");

        assertThat(proxyHits).hasValue(1);
        assertThat(targetHits).hasValue(0);
    }

    @Test
    @DisplayName("没有任务级覆盖时保持现有代理选择")
    void configuredProxiesRemainSelectedWithoutOverride() throws Exception {
        assertResponse(globalProxyClient().get(targetUri()), "proxy");
        assertResponse(customProxyClient().get(targetUri()), "proxy");

        assertThat(proxyHits).hasValue(2);
        assertThat(targetHits).hasValue(0);
    }

    @Test
    @DisplayName("短链显式代理配置无效时禁止静默直连")
    void invalidRedirectProxyDoesNotFallBackToDirect() {
        var invalidGlobalProxy = proxySettings(false, "", 0);
        DouyinPluginSettingsService invalidCustomProxy = DouyinPluginSettingsService.fixed(
                Path.of("."), DouyinProxyMode.CUSTOM, "http://127.0.0.1", 1080);

        assertThatThrownBy(() -> new RestTemplateDouyinRedirectClient(invalidGlobalProxy, true)
                .get(targetUri()))
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        assertThatThrownBy(() -> new RestTemplateDouyinRedirectClient(invalidCustomProxy)
                .get(targetUri()))
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        assertThat(targetHits).hasValue(0);
    }

    private RestTemplateDouyinRedirectClient globalProxyClient() {
        return new RestTemplateDouyinRedirectClient(proxySettings(
                true, "127.0.0.1", proxyServer.getAddress().getPort()));
    }

    private RestTemplateDouyinRedirectClient customProxyClient() {
        DouyinPluginSettingsService settingsService = DouyinPluginSettingsService.fixed(
                Path.of("."),
                DouyinProxyMode.CUSTOM,
                "127.0.0.1",
                proxyServer.getAddress().getPort());
        return new RestTemplateDouyinRedirectClient(settingsService);
    }

    private URI targetUri() {
        return URI.create("http://127.0.0.1:" + targetServer.getAddress().getPort() + "/probe");
    }

    private static HttpServer startServer(String responseBody, AtomicInteger hits) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var responseBodyStream = exchange.getResponseBody()) {
                responseBodyStream.write(body);
            }
        });
        server.start();
        return server;
    }

    private static void assertResponse(DouyinRedirectResponse response, String expectedBody) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(expectedBody);
    }
}
