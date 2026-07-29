package top.sywyar.pixivdownload.gui.panel.configtab;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.common.web.GuiActionInvocationHeaders;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GUI 配置动作本地客户端")
class GuiConfigTestClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("插件动作请求携带宿主绑定的 owner")
    void pluginActionCarriesBoundOwner() throws Exception {
        AtomicReference<String> owner = new AtomicReference<>();
        server = server(exchange -> {
            owner.set(exchange.getRequestHeaders().getFirst(GuiActionInvocationHeaders.PLUGIN_OWNER));
            respond(exchange, "{}".getBytes(StandardCharsets.UTF_8));
        });

        GuiConfigTestClient.Response response = new GuiConfigTestClient(server.getAddress().getPort())
                .postJson("mail/test", "{}".getBytes(StandardCharsets.UTF_8), 2_000, "mail");

        assertThat(response.reachable()).isTrue();
        assertThat(response.status()).isEqualTo(200);
        assertThat(owner).hasValue("mail");
    }

    @Test
    @DisplayName("插件动作响应超过上限时不保留部分正文")
    void oversizedPostResponseIsDiscarded() throws Exception {
        byte[] oversized = "x".repeat(64 * 1024 + 1).getBytes(StandardCharsets.UTF_8);
        server = server(exchange -> respond(exchange, oversized));

        GuiConfigTestClient.Response response = new GuiConfigTestClient(server.getAddress().getPort())
                .postJson("mail/test", new byte[0], 2_000, "mail");

        assertThat(response.reachable()).isTrue();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.bodyLimitExceeded()).isTrue();
        assertThat(response.body()).isEmpty();
    }

    @Test
    @DisplayName("上限内的 UTF-8 结构化响应保持完整")
    void boundedUtf8ResponseRemainsIntact() throws Exception {
        String json = "{\"reply\":\"连接成功😀\"}";
        server = server(exchange -> respond(exchange, json.getBytes(StandardCharsets.UTF_8)));

        GuiConfigTestClient.Response response = new GuiConfigTestClient(server.getAddress().getPort())
                .postJson("mail/test", new byte[0], 2_000, "mail");

        assertThat(response.reachable()).isTrue();
        assertThat(response.bodyLimitExceeded()).isFalse();
        assertThat(response.body()).isEqualTo(json);
    }

    private HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        created.createContext("/api/gui/mail/test", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        created.start();
        return created;
    }

    private static void respond(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
