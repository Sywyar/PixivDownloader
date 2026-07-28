package top.sywyar.pixivdownload.plugin.api.http.websocket;

import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;

import java.time.Duration;
import java.util.Objects;

/**
 * Transport-neutral connection and routing profile for one plugin-owned WebSocket client.
 */
public record OutboundWebSocketClientProfile(
        Duration connectTimeout,
        OutboundHttpRoute route
) {

    public OutboundWebSocketClientProfile {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        route = Objects.requireNonNull(route, "route");
    }
}
