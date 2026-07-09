package top.sywyar.pixivdownload.douyin.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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
}
