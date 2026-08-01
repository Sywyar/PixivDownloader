package top.sywyar.pixivdownload.config.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketClient;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketRequest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("宿主 JDK 出站 WebSocket 实现")
class JdkOutboundWebSocketClientFactoryTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(1_234);
    private static final URI REQUEST_URI = URI.create("wss://example.invalid/socket");

    @Test
    @DisplayName("每次连接动态解析路由并显式区分 HTTP 代理与直连")
    void resolvesRouteForEveryConnectionAndPassesStableRequestProfile() {
        ProxyConfig proxyConfig = new ProxyConfig();
        proxyConfig.setEnabled(true);
        proxyConfig.setHost("127.0.0.9");
        proxyConfig.setPort(7899);
        CapturingTransport transport = new CapturingTransport();
        WebSocket firstSocket = mock(WebSocket.class);
        WebSocket secondSocket = mock(WebSocket.class);
        transport.enqueue(CompletableFuture.completedFuture(firstSocket));
        transport.enqueue(CompletableFuture.completedFuture(secondSocket));
        OutboundWebSocketRequest request = request();

        try (OutboundWebSocketClient client = new JdkOutboundWebSocketClientFactory(
                proxyConfig, transport).open(profile(OutboundHttpRoute.inherit()))) {
            assertThat(client.connect(request, new WebSocket.Listener() {
            }).join()).isSameAs(firstSocket);

            proxyConfig.setEnabled(false);
            assertThat(client.connect(request, new WebSocket.Listener() {
            }).join()).isSameAs(secondSocket);
        }

        assertThat(transport.invocations).hasSize(2);
        CapturedHandshake proxied = transport.invocations.get(0);
        assertThat(proxied.connectTimeout()).isEqualTo(CONNECT_TIMEOUT);
        assertThat(proxied.request()).isSameAs(request);
        Proxy proxy = proxied.proxySelector()
                .select(URI.create("https://target.invalid"))
                .get(0);
        assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(proxy.address()).isInstanceOfSatisfying(
                InetSocketAddress.class,
                address -> {
                    assertThat(address.getHostString()).isEqualTo("127.0.0.9");
                    assertThat(address.getPort()).isEqualTo(7899);
                });

        CapturedHandshake direct = transport.invocations.get(1);
        assertThat(direct.proxySelector()
                .select(URI.create("https://target.invalid")))
                .containsExactly(Proxy.NO_PROXY);
        verify(firstSocket).abort();
        verify(secondSocket).abort();
    }

    @Test
    @DisplayName("取消返回 future 会传播到原握手并中止迟到 socket")
    void cancellationPropagatesToHandshakeAndAbortsLateSocket() {
        CapturingTransport transport = new CapturingTransport();
        TrackingHandshakeFuture handshake = new TrackingHandshakeFuture();
        transport.enqueue(handshake);
        OutboundWebSocketClient client = factory(transport)
                .open(profile(OutboundHttpRoute.direct()));
        WebSocket.Listener delegate = mock(WebSocket.Listener.class);

        CompletableFuture<WebSocket> result = client.connect(request(), delegate);
        assertThat(result.cancel(false)).isTrue();
        assertThat(handshake.cancelCalls).hasValue(1);
        assertThat(handshake.lastMayInterrupt).isFalse();

        WebSocket lateSocket = mock(WebSocket.class);
        assertThat(handshake.complete(lateSocket)).isTrue();
        verify(lateSocket).abort();

        transport.invocations.get(0).listener()
                .onText(lateSocket, "late", true);
        verify(delegate, never()).onText(lateSocket, "late", true);
        client.close();
    }

    @Test
    @DisplayName("关闭会取消 pending、终止 active 与迟到 socket、清理监听器并拒绝新连接")
    void closeDrainsTransportStateAndRejectsNewConnections() {
        CapturingTransport transport = new CapturingTransport();
        WebSocket activeSocket = mock(WebSocket.class);
        TrackingHandshakeFuture pendingHandshake = new TrackingHandshakeFuture();
        transport.enqueue(CompletableFuture.completedFuture(activeSocket));
        transport.enqueue(pendingHandshake);
        OutboundWebSocketClient client = factory(transport)
                .open(profile(OutboundHttpRoute.direct()));
        WebSocket.Listener activeDelegate = mock(WebSocket.Listener.class);
        WebSocket.Listener pendingDelegate = mock(WebSocket.Listener.class);

        assertThat(client.connect(request(), activeDelegate).join())
                .isSameAs(activeSocket);
        CompletableFuture<WebSocket> pending =
                client.connect(request(), pendingDelegate);

        client.close();
        client.close();

        assertThat(pending).isCancelled();
        assertThat(pendingHandshake.cancelCalls).hasValue(1);
        verify(activeSocket).abort();

        WebSocket lateSocket = mock(WebSocket.class);
        assertThat(pendingHandshake.complete(lateSocket)).isTrue();
        verify(lateSocket).abort();

        transport.invocations.get(0).listener()
                .onText(activeSocket, "ignored", true);
        transport.invocations.get(1).listener()
                .onText(lateSocket, "ignored", true);
        verify(activeDelegate, never()).onText(activeSocket, "ignored", true);
        verify(pendingDelegate, never()).onText(lateSocket, "ignored", true);
        verify(activeSocket, atLeastOnce()).abort();
        verify(lateSocket, atLeastOnce()).abort();

        assertThatThrownBy(() -> client.connect(
                request(), new WebSocket.Listener() {
                }).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThat(transport.invocations).hasSize(2);
    }

    @Test
    @DisplayName("握手失败保留原始 JDK 异常与响应元数据")
    void preservesOriginalHandshakeFailureAndResponseMetadata() {
        HttpResponse<?> response = mock(HttpResponse.class);
        HttpHeaders headers = HttpHeaders.of(
                Map.of("Date", List.of("Tue, 28 Jul 2026 09:00:00 GMT")),
                (name, value) -> true);
        when(response.statusCode()).thenReturn(403);
        when(response.headers()).thenReturn(headers);
        WebSocketHandshakeException expected =
                new WebSocketHandshakeException(response);
        CompletableFuture<WebSocket> handshake = new CompletableFuture<>();
        CapturingTransport transport = new CapturingTransport();
        transport.enqueue(handshake);
        OutboundWebSocketClient client = factory(transport)
                .open(profile(OutboundHttpRoute.direct()));

        CompletableFuture<WebSocket> result =
                client.connect(request(), new WebSocket.Listener() {
                });
        handshake.completeExceptionally(expected);

        assertThatThrownBy(result::join)
                .isInstanceOf(CompletionException.class)
                .satisfies(failure ->
                        assertThat(failure.getCause()).isSameAs(expected));
        assertThat(expected.getResponse().statusCode()).isEqualTo(403);
        assertThat(expected.getResponse().headers().firstValue("Date"))
                .contains("Tue, 28 Jul 2026 09:00:00 GMT");
        client.close();
    }

    @Test
    @DisplayName("真实 JDK 握手透传自定义头并保留非 101 响应 Date")
    void realJdkHandshakePassesHeadersAndRetainsResponseDate() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
            CompletableFuture<List<String>> requestLines = new CompletableFuture<>();
            serverExecutor.submit(() -> serveRejectedHandshake(serverSocket, requestLines));
            ProxyConfig proxyConfig = new ProxyConfig();
            OutboundWebSocketClient client =
                    new JdkOutboundWebSocketClientFactory(proxyConfig)
                            .open(profile(OutboundHttpRoute.direct()));
            URI uri = URI.create("ws://127.0.0.1:"
                    + serverSocket.getLocalPort()
                    + "/socket?signature=%2B%2F%3D");

            try {
                CompletableFuture<WebSocket> result = client.connect(
                        new OutboundWebSocketRequest(uri, Map.of(
                                "X-Test", List.of("first", "second"))),
                        new WebSocket.Listener() {
                        });

                assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .satisfies(failure -> {
                            assertThat(failure.getCause())
                                    .isInstanceOf(WebSocketHandshakeException.class);
                            WebSocketHandshakeException handshakeFailure =
                                    (WebSocketHandshakeException) failure.getCause();
                            assertThat(handshakeFailure.getResponse().statusCode())
                                    .isEqualTo(403);
                            assertThat(handshakeFailure.getResponse().headers()
                                    .firstValue("Date"))
                                    .contains("Tue, 28 Jul 2026 09:00:00 GMT");
                        });

                String rawRequest = String.join("\n",
                        requestLines.get(5, TimeUnit.SECONDS));
                assertThat(rawRequest)
                        .contains("GET /socket?signature=%2B%2F%3D HTTP/1.1")
                        .containsIgnoringCase("X-Test: first")
                        .containsIgnoringCase("X-Test: second");
            } finally {
                client.close();
                serverSocket.close();
                serverExecutor.shutdownNow();
                assertThat(serverExecutor.awaitTermination(5, TimeUnit.SECONDS))
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("JDK 拒绝受限握手头时以原始同步异常完成返回 future")
    void restrictedJdkHeaderFailsReturnedFutureWithoutStartingNetwork() {
        ProxyConfig proxyConfig = new ProxyConfig();
        OutboundWebSocketClient client =
                new JdkOutboundWebSocketClientFactory(proxyConfig)
                        .open(profile(OutboundHttpRoute.direct()));
        OutboundWebSocketRequest request = new OutboundWebSocketRequest(
                URI.create("ws://127.0.0.1:1/socket"),
                Map.of("Sec-WebSocket-Key", List.of("caller-owned")));

        CompletableFuture<WebSocket> result = client.connect(
                request,
                new WebSocket.Listener() {
                });

        assertThatThrownBy(result::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sec-WebSocket-Key");
        client.close();
    }

    @Test
    @DisplayName("父配置暴露中性 factory 且每次 open 返回独立客户端")
    void parentConfigurationExposesUncachedStableFactory() {
        ProxyConfig proxyConfig = new ProxyConfig();
        OutboundHttpClientConfiguration configuration =
                new OutboundHttpClientConfiguration(proxyConfig);
        OutboundWebSocketClientFactory factory =
                configuration.outboundWebSocketClientFactory();

        OutboundWebSocketClient first =
                factory.open(profile(OutboundHttpRoute.direct()));
        OutboundWebSocketClient second =
                factory.open(profile(OutboundHttpRoute.direct()));

        assertThat(factory).isInstanceOf(JdkOutboundWebSocketClientFactory.class);
        assertThat(first).isNotSameAs(second);
        first.close();
        second.close();
    }

    private static void serveRejectedHandshake(
            ServerSocket serverSocket,
            CompletableFuture<List<String>> requestLines
    ) {
        try (Socket socket = serverSocket.accept();
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.ISO_8859_1));
             OutputStream output = socket.getOutputStream()) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                lines.add(line);
            }
            requestLines.complete(List.copyOf(lines));
            output.write(("HTTP/1.1 403 Forbidden\r\n"
                    + "Date: Tue, 28 Jul 2026 09:00:00 GMT\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: close\r\n"
                    + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
        } catch (Throwable failure) {
            requestLines.completeExceptionally(failure);
        }
    }

    private static JdkOutboundWebSocketClientFactory factory(
            CapturingTransport transport
    ) {
        ProxyConfig proxyConfig = new ProxyConfig();
        proxyConfig.setEnabled(false);
        return new JdkOutboundWebSocketClientFactory(proxyConfig, transport);
    }

    private static OutboundWebSocketClientProfile profile(
            OutboundHttpRoute route
    ) {
        return new OutboundWebSocketClientProfile(CONNECT_TIMEOUT, route);
    }

    private static OutboundWebSocketRequest request() {
        return new OutboundWebSocketRequest(
                REQUEST_URI,
                Map.of("X-Test", List.of("value")));
    }

    private static final class CapturingTransport
            implements JdkOutboundWebSocketClientFactory.HandshakeTransport {

        private final Deque<CompletableFuture<WebSocket>> handshakes =
                new ArrayDeque<>();
        private final List<CapturedHandshake> invocations = new ArrayList<>();

        private void enqueue(CompletableFuture<WebSocket> handshake) {
            handshakes.addLast(handshake);
        }

        @Override
        public CompletableFuture<WebSocket> connect(
                Duration connectTimeout,
                ProxySelector proxySelector,
                OutboundWebSocketRequest request,
                WebSocket.Listener listener
        ) {
            invocations.add(new CapturedHandshake(
                    connectTimeout,
                    proxySelector,
                    request,
                    listener));
            return handshakes.removeFirst();
        }
    }

    private record CapturedHandshake(
            Duration connectTimeout,
            ProxySelector proxySelector,
            OutboundWebSocketRequest request,
            WebSocket.Listener listener
    ) {
    }

    private static final class TrackingHandshakeFuture
            extends CompletableFuture<WebSocket> {

        private final AtomicInteger cancelCalls = new AtomicInteger();
        private volatile boolean lastMayInterrupt;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            lastMayInterrupt = mayInterruptIfRunning;
            return true;
        }
    }
}
