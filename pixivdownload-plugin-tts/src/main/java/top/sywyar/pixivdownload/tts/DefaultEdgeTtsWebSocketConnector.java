package top.sywyar.pixivdownload.tts;

import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketClient;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketRequest;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** 通过宿主稳定 WebSocket 契约建立 Edge TTS 连接。 */
final class DefaultEdgeTtsWebSocketConnector implements EdgeTtsWebSocketConnector {

    private final OutboundWebSocketClient client;
    private final EdgeTtsVersionService versionService;

    DefaultEdgeTtsWebSocketConnector(
            OutboundWebSocketClient client,
            EdgeTtsVersionService versionService
    ) {
        this.client = client;
        this.versionService = versionService;
    }

    @Override
    public CompletableFuture<WebSocket> connect(URI uri, WebSocket.Listener listener) {
        return client.connect(new OutboundWebSocketRequest(uri, Map.of(
                "User-Agent", List.of(versionService.userAgent()),
                "Origin", List.of("chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"),
                "Pragma", List.of("no-cache"),
                "Cache-Control", List.of("no-cache"),
                "Accept-Language", List.of("en-US,en;q=0.9"))), listener);
    }
}
