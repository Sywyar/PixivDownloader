package top.sywyar.pixivdownload.plugin.api.http.websocket;

/**
 * Stable host capability for opening plugin-owned outbound WebSocket clients.
 */
@FunctionalInterface
public interface OutboundWebSocketClientFactory {

    OutboundWebSocketClient open(OutboundWebSocketClientProfile profile);
}
