package top.sywyar.pixivdownload.gui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.config.http.LocalGuiWebServerCustomizer;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.GuiActionInvocationHeaders;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DesktopUiLocalApiClientTest {
    private HttpServer server;
    private org.springframework.context.annotation.AnnotationConfigApplicationContext context;
    private AutoCloseable registration;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("普通与开发模式都跟随本机入口换代，后端未运行时不访问公开端口")
    void requestsFollowCurrentBackendEndpoint(boolean development) throws Exception {
        String property = "pixivdownload.plugin-dev.enabled";
        String previous = System.getProperty(property);
        BackendLifecycleManager.resetForTests();
        try {
            System.setProperty(property, Boolean.toString(development));
            server = server(exchange -> respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8)));
            var host = new AppDesktopUiHost(port());
            assertThat(host.guiGet("status", 2_000).reachable()).isFalse();
            for (int attempt = 0; attempt < 2; attempt++) {
                if (attempt > 0) {
                    server.stop(0);
                    server = server(exchange -> respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8)));
                }
                try {
                    startBackend("generation-" + attempt);
                    assertThat(host.backendPort(1)).isEqualTo(18443);
                    assertThat(host.backendUri("/invite?code=test").toString())
                            .isEqualTo("https://unreachable.invalid:18443/invite?code=test");
                    assertThat(host.guiGet("status", 2_000).successful()).isTrue();
                } finally {
                    stopBackend();
                }
                assertThat(host.guiGet("status", 2_000).reachable()).isFalse();
            }
        } finally {
            BackendLifecycleManager.resetForTests();
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    @DisplayName("命令行公开端口覆盖不影响管理员初始化与插件状态使用本机入口")
    void launchPortOverrideReachesSetupAndPluginStatus() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        server = server(exchange -> {
            requestPath.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        });
        int configuredPort = port() == 65_535 ? port() - 1 : port() + 1;
        assertThat(GuiLauncher.resolveServerPort(configuredPort, new String[0])).isEqualTo(configuredPort);
        AppDesktopUiHost host = new AppDesktopUiHost(GuiLauncher.resolveServerPort(
                configuredPort,
                new String[]{"--server.address=127.0.0.1", "--server.port=" + port()}
        ));
        startBackend("setup-token");

        assertThat(host.guiPostJson("setup/init", Map.of(), 2_000).successful()).isTrue();
        assertThat(requestPath).hasValue("POST /api/gui/setup/init");
        assertThat(host.guiGet("plugins/status", 2_000).successful()).isTrue();
        assertThat(requestPath).hasValue("GET /api/gui/plugins/status");
    }

    @AfterEach
    void stopServer() throws Exception {
        stopBackend();
        if (server != null) server.stop(0);
        GuiTokenHolder.set(null);
    }

    @Test
    @DisplayName("携带同一实例的令牌及动作属主并解析 UTF-8 错误响应")
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

        DesktopUiHost.GuiResponse response = client("test-token").exchange(
                DesktopUiHost.GuiRequest.json("mail/test", Map.of("path", "迁移目录"), 2_000, "mail"));

        assertThat(response.reachable()).isTrue();
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().path("error").asText()).isEqualTo("目录冲突");
        assertThat(token).hasValue("test-token");
        assertThat(owner).hasValue("mail");
    }

    @Test
    @DisplayName("丢弃超出响应上限的正文")
    void discardsOversizedResponse() throws Exception {
        byte[] oversized = "x".repeat(64 * 1024 + 1).getBytes(StandardCharsets.UTF_8);
        server = server(exchange -> respond(exchange, 200, oversized));

        DesktopUiHost.GuiResponse response = client("test-token").exchange(
                DesktopUiHost.GuiRequest.json("mail/test", Map.of(), 2_000, "mail"));

        assertThat(response.bodyLimitExceeded()).isTrue();
        assertThat(response.body()).isNull();
        assertThat(response.rawBody()).isEmpty();
    }

    @Test
    @DisplayName("未知响应长度时按实际字节执行上限并保留边界内的 UTF-8 内容")
    void boundsChunkedResponsesByActualBytes() throws Exception {
        AtomicReference<byte[]> payload = new AtomicReference<>("汉".repeat(100).getBytes(StandardCharsets.UTF_8));
        server = server(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) { output.write(payload.get()); }
        });
        var client = client("test-token");
        var request = DesktopUiHost.GuiRequest.json("mail/test", Map.of(), 2_000, "mail");
        assertThat(client.exchange(request).rawBody()).isEqualTo("汉".repeat(100));
        payload.set("x".repeat(64 * 1024).getBytes(StandardCharsets.UTF_8));
        var boundary = client.exchange(request);
        assertThat(boundary.bodyLimitExceeded()).isFalse();
        assertThat(boundary.rawBody()).hasSize(64 * 1024);
        payload.set("x".repeat(64 * 1024 + 1).getBytes(StandardCharsets.UTF_8));
        var oversized = client.exchange(request);
        assertThat(oversized.bodyLimitExceeded()).isTrue();
        assertThat(oversized.rawBody()).isEmpty();
    }

    @Test
    @DisplayName("非 JSON 正文保留 HTTP 结果且不生成解析数据")
    void invalidJsonKeepsSuccessfulHttpResponseWithoutParsedBody() throws Exception {
        server = server(exchange -> respond(exchange, 200, "{invalid".getBytes(StandardCharsets.UTF_8)));

        DesktopUiHost.GuiResponse response = client("test-token").exchange(
                DesktopUiHost.GuiRequest.get("status", 2_000));

        assertThat(response.successful()).isTrue();
        assertThat(response.responseParsed()).isFalse();
    }

    @Test
    @DisplayName("完整进程重启通过当前后端的已认证本机入口请求")
    void desktopHostRequestsFullRestartThroughAuthenticatedGuiEndpoint() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        server = server(exchange -> {
            method.set(exchange.getRequestMethod());
            token.set(exchange.getRequestHeaders().getFirst(GuiTokenHolder.HEADER_NAME));
            respond(exchange, 200, new byte[0]);
        });
        startBackend("restart-token");
        GuiTokenHolder.set("unrelated-token");

        assertThat(new AppDesktopUiHost(port()).restartApplication()).isTrue();
        assertThat(method).hasValue("POST");
        assertThat(token).hasValue("restart-token");
    }

    @Test
    @DisplayName("系统代理即使无法连接也不接收 GUI 请求或令牌")
    void bypassesProxySelector() throws Exception {
        var previous = ProxySelector.getDefault();
        var selections = new AtomicInteger();
        server = server(exchange -> respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8)));
        try {
            ProxySelector.setDefault(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) {
                    selections.incrementAndGet();
                    return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1)));
                }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
            });
            assertThat(client("local-token").exchange(DesktopUiHost.GuiRequest.get("status", 2_000))
                    .successful()).isTrue();
            assertThat(selections).hasValue(0);
        } finally {
            ProxySelector.setDefault(previous);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308, 400, 401, 500})
    @DisplayName("重定向及错误响应只返回原始结果，不发送第二次请求")
    void doesNotFollowRedirectsOrRetryErrors(int status) throws Exception {
        var requests = new AtomicInteger();
        server = server(exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + port() + "/api/gui/restart");
            respond(exchange, status, "{}".getBytes(StandardCharsets.UTF_8));
        });
        var client = client("local-token");
        var result = client.exchange(DesktopUiHost.GuiRequest.get("status", 2_000));
        assertThat(result.reachable()).isTrue();
        assertThat(result.status()).isEqualTo(status);
        assertThat(requests).hasValue(1);
        result = client.exchange(DesktopUiHost.GuiRequest.json("restart", Map.of(), 2_000, null));
        assertThat(result.status()).isEqualTo(status);
        assertThat(requests).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("服务端执行写操作后断连不会自动重放，包括无正文 POST")
    void doesNotReplayPostAfterLostResponse(boolean withBody) throws Exception {
        var writes = new AtomicInteger();
        server = server(exchange -> {
            exchange.getRequestBody().readAllBytes();
            writes.incrementAndGet();
            exchange.close();
        });
        var request = withBody ? DesktopUiHost.GuiRequest.json("restart", Map.of(), 2_000, null)
                : DesktopUiHost.GuiRequest.form("POST", "restart", null, 2_000);
        assertThat(client("local-token").exchange(request).reachable()).isFalse();
        assertThat(writes).hasValue(1);
    }

    @Test
    @DisplayName("本机链路断开不改变后端已运行状态")
    void transportFailureDoesNotBecomeStartupFailure() throws Exception {
        server = server(exchange -> respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8)));
        startBackend("local-token");
        server.stop(0);
        assertThat(new AppDesktopUiHost(port()).guiGet("status", 2_000).reachable()).isFalse();
        assertThat(BackendLifecycleManager.state()).isEqualTo(BackendLifecycleManager.State.RUNNING);
    }

    private DesktopUiLocalApiClient client(String token) {
        return new DesktopUiLocalApiClient(() -> new LocalGuiWebServerCustomizer.Connection(port(), token));
    }

    private void startBackend(String token) throws Exception {
        BackendLifecycleManager.resetForTests();
        context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "public-address", Map.of("local.server.port", 18443, "server.ssl.enabled", "true")));
        context.registerBean(top.sywyar.pixivdownload.config.SslConfig.class, () -> {
            var ssl = new top.sywyar.pixivdownload.config.SslConfig();
            ssl.setDomain("unreachable.invalid");
            return ssl;
        });
        context.registerBean(top.sywyar.pixivdownload.config.http.ServerAddressProvider.class);
        var endpoint = mock(LocalGuiWebServerCustomizer.class);
        // 真实监听的绑定、启停和鉴权由连接器集成测试覆盖，此处验证宿主跟随上下文换代。
        when(endpoint.connection()).thenReturn(new LocalGuiWebServerCustomizer.Connection(port(), token));
        context.getBeanFactory().registerSingleton("localGuiEndpoint", endpoint);
        context.refresh();
        var ready = new java.util.concurrent.CompletableFuture<Void>();
        registration = BackendLifecycleManager.configure(new String[0], ready::completeExceptionally, args -> context);
        assertThat(BackendLifecycleManager.startAsync(() -> ready.complete(null))).isTrue();
        ready.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void stopBackend() throws Exception {
        BackendLifecycleManager.resetForTests();
        if (registration != null) registration.close();
        registration = null;
        if (context != null) context.close();
        context = null;
    }

    private HttpServer server(Handler handler) throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        created.createContext("/api/gui/mail/test", exchange -> handle(exchange, handler));
        created.createContext("/api/gui/status", exchange -> handle(exchange, handler));
        created.createContext("/api/gui/restart", exchange -> handle(exchange, handler));
        created.createContext("/api/gui/setup/init", exchange -> handle(exchange, handler));
        created.createContext("/api/gui/plugins/status", exchange -> handle(exchange, handler));
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
