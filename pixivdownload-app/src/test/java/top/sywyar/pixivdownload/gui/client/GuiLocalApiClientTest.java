package top.sywyar.pixivdownload.gui.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.GuiTokenHolder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("桌面 GUI 本地 API 客户端")
class GuiLocalApiClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("状态探测携带 GUI 令牌并解析 UTF-8 JSON")
    void fetchStatusCarriesTokenAndParsesJson() throws Exception {
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        AtomicReference<String> receivedToken = new AtomicReference<>();
        server = newServer();
        server.createContext("/api/gui/status", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            receivedToken.set(exchange.getRequestHeaders().getFirst(GuiTokenHolder.HEADER_NAME));
            respondJson(exchange, 200, "{\"mode\":\"solo\",\"domain\":\"本机\"}");
        });
        server.start();

        GuiLocalApiClient client = new GuiLocalApiClient(port(), () -> "gui-test-token");
        GuiLocalApiClient.StatusResponse response = client.fetchStatus();

        assertThat(response.successful()).isTrue();
        assertThat(response.responseParsed()).isTrue();
        assertThat(response.body().path("mode").asText()).isEqualTo("solo");
        assertThat(response.body().path("domain").asText()).isEqualTo("本机");
        assertThat(receivedMethod).hasValue("GET");
        assertThat(receivedToken).hasValue("gui-test-token");
    }

    @Test
    @DisplayName("状态端点返回非 200 时不再探测备用协议")
    void fetchStatusStopsAfterHttpResponse() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = newServer();
        server.createContext("/api/gui/status", exchange -> {
            requests.incrementAndGet();
            respondJson(exchange, 503, "{\"error\":\"maintenance\"}");
        });
        server.start();

        GuiLocalApiClient.StatusResponse response =
                new GuiLocalApiClient(port(), () -> null).fetchStatus();

        assertThat(response.successful()).isFalse();
        assertThat(response.responseParsed()).isFalse();
        assertThat(response.body()).isNull();
        assertThat(requests).hasValue(1);
    }

    @Test
    @DisplayName("状态端点返回无效 JSON 时保留已响应但不投影视图的语义")
    void fetchStatusSeparatesSuccessfulResponseFromParsedBody() throws Exception {
        server = newServer();
        server.createContext("/api/gui/status", exchange -> respondJson(exchange, 200, "{invalid-json"));
        server.start();

        GuiLocalApiClient.StatusResponse response =
                new GuiLocalApiClient(port(), () -> null).fetchStatus();

        assertThat(response.successful()).isTrue();
        assertThat(response.responseParsed()).isFalse();
        assertThat(response.body()).isNull();
    }

    @Test
    @DisplayName("JSON 请求使用 UTF-8 并解析错误响应体")
    void exchangeJsonUsesUtf8AndParsesErrorBody() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedContentType = new AtomicReference<>();
        server = newServer();
        server.createContext("/api/gui/path-prefixes", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            respondJson(exchange, 409, "{\"success\":false,\"error\":\"目录冲突\"}");
        });
        server.start();

        var response = new GuiLocalApiClient(port(), () -> "token").exchangeJson(
                "POST",
                "/api/gui/path-prefixes",
                "{\"path\":\"迁移目录\"}",
                2_000,
                "gui.status.log.path-prefix.call-failed");

        assertThat(receivedBody).hasValue("{\"path\":\"迁移目录\"}");
        assertThat(receivedContentType.get()).isEqualToIgnoringCase("application/json; charset=utf-8");
        assertThat(response).isNotNull();
        assertThat(response.path("success").asBoolean()).isFalse();
        assertThat(response.path("error").asText()).isEqualTo("目录冲突");
    }

    @Test
    @DisplayName("204 响应保持无结果语义")
    void exchangeReturnsNullForNoContent() throws Exception {
        server = newServer();
        server.createContext("/api/gui/update/last", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        var response = new GuiLocalApiClient(port(), () -> null).exchangeForm(
                "GET",
                "/api/gui/update/last",
                null,
                2_000,
                "gui.status.log.update.call-failed");

        assertThat(response).isNull();
    }

    private static HttpServer newServer() throws IOException {
        return HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
