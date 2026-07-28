package top.sywyar.pixivdownload.plugin.api.http.websocket;

import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;

/**
 * Closeable outbound WebSocket transport opened by the host for one plugin-owned profile.
 *
 * <p>The future returned by {@link #connect(OutboundWebSocketRequest, WebSocket.Listener)}
 * represents the transport handshake itself. Cancelling it must cancel the underlying handshake,
 * and handshake failures must retain the original JDK transport exception, including
 * {@code WebSocketHandshakeException} response metadata.
 */
public interface OutboundWebSocketClient extends AutoCloseable {

    /**
     * Starts one WebSocket handshake.
     *
     * @param request transport-neutral target and handshake headers
     * @param listener caller-owned JDK WebSocket listener
     * @return the cancellable transport handshake future
     */
    CompletableFuture<WebSocket> connect(
            OutboundWebSocketRequest request,
            WebSocket.Listener listener
    );

    /**
     * Releases all transport resources.
     *
     * <p>Implementations must make repeated calls safe, reject new connections after closing,
     * cancel pending handshakes, and abort both active sockets and sockets that complete after
     * closing.
     */
    @Override
    void close();
}
