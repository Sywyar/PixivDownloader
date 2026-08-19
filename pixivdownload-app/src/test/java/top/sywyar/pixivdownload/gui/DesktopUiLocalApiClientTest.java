package top.sywyar.pixivdownload.gui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.GuiActionInvocationHeaders;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopUiLocalApiClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        GuiTokenHolder.set(null);
    }

    @Test
    void carriesTokenOwnerAndParsesUtf8Json() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> owner = new AtomicReference<>();
        server = server(exchange -> {
            token.set(exchange.getRequestHeaders().getFirst(GuiTokenHolder.HEADER_NAME));
            owner.set(exchange.getRequestHeaders().getFirst(GuiActionInvocationHeaders.PLUGIN_OWNER));
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("{\"path\":\"迁移目录\"}");
            respond(exchange, 409, "{\"error\":\"目录冲突\"}".getBytes(StandardCharsets.UTF_8));
        });
        GuiTokenHolder.set("test-token");

        DesktopUiHost.GuiResponse response = new DesktopUiLocalApiClient(port()).exchange(
                DesktopUiHost.GuiRequest.json("mail/test", Map.of("path", "迁移目录"), 2_000, "mail"));

        assertThat(response.reachable()).isTrue();
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().path("error").asText()).isEqualTo("目录冲突");
        assertThat(token).hasValue("test-token");
        assertThat(owner).hasValue("mail");
    }

    @Test
    void discardsOversizedResponse() throws Exception {
        byte[] oversized = "x".repeat(64 * 1024 + 1).getBytes(StandardCharsets.UTF_8);
        server = server(exchange -> respond(exchange, 200, oversized));

        DesktopUiHost.GuiResponse response = new DesktopUiLocalApiClient(port()).exchange(
                DesktopUiHost.GuiRequest.json("mail/test", Map.of(), 2_000, "mail"));

        assertThat(response.bodyLimitExceeded()).isTrue();
        assertThat(response.body()).isNull();
        assertThat(response.rawBody()).isEmpty();
    }

    @Test
    void invalidJsonKeepsSuccessfulHttpResponseWithoutParsedBody() throws Exception {
        server = server(exchange -> respond(exchange, 200, "{invalid".getBytes(StandardCharsets.UTF_8)));

        DesktopUiHost.GuiResponse response = new DesktopUiLocalApiClient(port()).exchange(
                DesktopUiHost.GuiRequest.get("status", 2_000));

        assertThat(response.successful()).isTrue();
        assertThat(response.responseParsed()).isFalse();
    }

    @Test
    void desktopHostRequestsFullRestartThroughAuthenticatedGuiEndpoint() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        server = server(exchange -> {
            method.set(exchange.getRequestMethod());
            token.set(exchange.getRequestHeaders().getFirst(GuiTokenHolder.HEADER_NAME));
            respond(exchange, 200, new byte[0]);
        });
        GuiTokenHolder.set("restart-token");

        assertThat(new AppDesktopUiHost(port()).restartApplication()).isTrue();
        assertThat(method).hasValue("POST");
        assertThat(token).hasValue("restart-token");
    }

    private HttpServer server(Handler handler) throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        created.createContext("/api/gui/mail/test", exchange -> handle(exchange, handler));
        created.createContext("/api/gui/status", exchange -> handle(exchange, handler));
        created.createContext("/api/gui/restart", exchange -> handle(exchange, handler));
        created.start();
        return created;
    }

    private static void handle(HttpExchange exchange, Handler handler) throws IOException {
        try { handler.handle(exchange); } finally { exchange.close(); }
    }

    private int port() { return server.getAddress().getPort(); }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @FunctionalInterface
    private interface Handler { void handle(HttpExchange exchange) throws IOException; }
}
