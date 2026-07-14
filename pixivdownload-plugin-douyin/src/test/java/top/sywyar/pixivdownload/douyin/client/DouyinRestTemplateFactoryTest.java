package top.sywyar.pixivdownload.douyin.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.ResourceAccessException;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.settings.DouyinProxyMode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Douyin HTTP 客户端重定向边界")
class DouyinRestTemplateFactoryTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("下载客户端不自动跟随未验证重定向")
    void downloadClientDoesNotFollowRedirects() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicInteger targetHits = new AtomicInteger();
        server.createContext("/source", exchange -> {
            exchange.getResponseHeaders().set("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        URI source = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/source");

        var response = DouyinRestTemplateFactory.directDownloadTemplate()
                .exchange(source, HttpMethod.GET, null, byte[].class);

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(targetHits).hasValue(0);
    }

    @Test
    @DisplayName("显式代理模式配置无效时禁止静默直连")
    void invalidExplicitProxyDoesNotFallBackToDirect() throws IOException {
        AtomicInteger targetHits = new AtomicInteger();
        URI target = startTarget(targetHits);
        ProxyConfig invalidGlobalProxy = new ProxyConfig();
        invalidGlobalProxy.setHost("");
        invalidGlobalProxy.setPort(0);
        DouyinPluginSettingsService invalidCustomProxy = DouyinPluginSettingsService.fixed(
                Path.of("."), DouyinProxyMode.CUSTOM, "http://127.0.0.1", 1080);

        assertThatThrownBy(() -> DouyinRestTemplateFactory.forcedProxyDownloadTemplate(invalidGlobalProxy)
                .exchange(target, HttpMethod.GET, null, byte[].class))
                .isInstanceOf(ResourceAccessException.class);
        assertThatThrownBy(() -> DouyinRestTemplateFactory.customProxyDownloadTemplate(invalidCustomProxy)
                .exchange(target, HttpMethod.GET, null, byte[].class))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(targetHits).hasValue(0);
    }

    @Test
    @DisplayName("上游 Set-Cookie 不会进入共享客户端并泄露给后续匿名请求")
    void responseCookiesAreNotRetainedAcrossRequests() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicInteger requestIndex = new AtomicInteger();
        AtomicBoolean firstRequestHadCookie = new AtomicBoolean();
        AtomicBoolean secondRequestHadCookie = new AtomicBoolean();
        server.createContext("/", exchange -> {
            int index = requestIndex.incrementAndGet();
            boolean hasCookie = exchange.getRequestHeaders().getFirst(HttpHeaders.COOKIE) != null;
            if (index == 1) {
                firstRequestHadCookie.set(hasCookie);
                exchange.getResponseHeaders().add(HttpHeaders.SET_COOKIE,
                        "upstream_session=synthetic; Path=/");
            } else if (index == 2) {
                secondRequestHadCookie.set(hasCookie);
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        URI target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        var template = DouyinRestTemplateFactory.directDownloadTemplate();
        HttpHeaders credentialedHeaders = new HttpHeaders();
        credentialedHeaders.set(HttpHeaders.COOKIE, "sessionid=synthetic");

        template.exchange(target, HttpMethod.GET,
                new HttpEntity<>(credentialedHeaders), byte[].class);
        template.exchange(target, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), byte[].class);

        assertThat(requestIndex).hasValue(2);
        assertThat(firstRequestHadCookie).isTrue();
        assertThat(secondRequestHadCookie).isFalse();
    }

    private URI startTarget(AtomicInteger targetHits) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            targetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }
}
