package top.sywyar.pixivdownload.tts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketClient;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketRequest;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Edge TTS WebSocket 协议连接器")
class DefaultEdgeTtsWebSocketConnectorTest {

    @Test
    @DisplayName("插件声明 Edge URI 与业务请求头并原样转交监听器")
    void contributesEdgeRequestWithoutOwningTransport() {
        CapturingClient client = new CapturingClient();
        EdgeTtsVersionService versionService = mock(EdgeTtsVersionService.class);
        when(versionService.userAgent()).thenReturn("edge-test-agent");
        DefaultEdgeTtsWebSocketConnector connector =
                new DefaultEdgeTtsWebSocketConnector(client, versionService);
        URI uri = URI.create("wss://speech.platform.bing.com/edge");
        WebSocket.Listener listener = mock(WebSocket.Listener.class);

        CompletableFuture<WebSocket> connection = connector.connect(uri, listener);

        assertThat(connection).isSameAs(client.connection);
        assertThat(client.request.uri()).isEqualTo(uri);
        assertThat(client.listener).isSameAs(listener);
        assertThat(header(client.request, "User-Agent")).containsExactly("edge-test-agent");
        assertThat(header(client.request, "Origin"))
                .containsExactly("chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold");
        assertThat(header(client.request, "Pragma")).containsExactly("no-cache");
        assertThat(header(client.request, "Cache-Control")).containsExactly("no-cache");
        assertThat(header(client.request, "Accept-Language"))
                .containsExactly("en-US,en;q=0.9");
    }

    private static List<String> header(OutboundWebSocketRequest request, String name) {
        return request.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
    }

    private static final class CapturingClient implements OutboundWebSocketClient {

        private final CompletableFuture<WebSocket> connection = new CompletableFuture<>();
        private OutboundWebSocketRequest request;
        private WebSocket.Listener listener;

        @Override
        public CompletableFuture<WebSocket> connect(
                OutboundWebSocketRequest request,
                WebSocket.Listener listener
        ) {
            this.request = request;
            this.listener = listener;
            return connection;
        }

        @Override
        public void close() {
        }
    }
}
