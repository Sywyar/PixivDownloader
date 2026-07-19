package top.sywyar.pixivdownload.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Pixiv 稳定端口 HTTP 客户端重定向策略")
class RestTemplateConfigRedirectTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Pixiv 专用客户端不跟随重定向且不跨请求保存 Cookie")
    void pixivClientsShouldNotFollowRedirectsOrRetainCookies() throws Exception {
        String sentinelCookie = "PHPSESSID=redirect-sentinel";
        List<String> firstHopCookies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger redirectedTargetHits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/redirect", exchange -> {
            firstHopCookies.add(String.valueOf(exchange.getRequestHeaders().getFirst(HttpHeaders.COOKIE)));
            exchange.getResponseHeaders().add(HttpHeaders.SET_COOKIE, "SERVER_COOKIE=must-not-stick; Path=/");
            exchange.getResponseHeaders().add("Location", localhostEndpoint("/target").toString());
            exchange.sendResponseHeaders(HttpStatus.FOUND.value(), -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            redirectedTargetHits.incrementAndGet();
            exchange.sendResponseHeaders(HttpStatus.OK.value(), -1);
            exchange.close();
        });
        server.start();

        ProxyConfig proxyConfig = new ProxyConfig();
        proxyConfig.setEnabled(false);
        RestTemplateConfig config = new RestTemplateConfig(proxyConfig);

        assertRedirectAndCookieIsolation(config.pixivCredentialRestTemplate(), sentinelCookie);
        assertRedirectAndCookieIsolation(config.pixivImageRestTemplate(), sentinelCookie);
        assertThat(firstHopCookies).containsExactly(sentinelCookie, "null", sentinelCookie, "null");
        assertThat(redirectedTargetHits).hasValue(0);
    }

    private void assertRedirectAndCookieIsolation(RestTemplate restTemplate, String sentinelCookie) {
        assertRedirectNotFollowed(restTemplate, sentinelCookie);
        assertRedirectNotFollowed(restTemplate, null);
    }

    private void assertRedirectNotFollowed(RestTemplate restTemplate, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        ResponseEntity<byte[]> response = restTemplate.exchange(
                endpoint("/redirect"), HttpMethod.GET, new HttpEntity<Void>(headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    }

    private URI endpoint(String path) {
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + path);
    }

    private URI localhostEndpoint(String path) {
        return URI.create("http://localhost:" + server.getAddress().getPort() + path);
    }
}
