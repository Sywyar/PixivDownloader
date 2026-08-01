package top.sywyar.pixivdownload.plugin.api.http;

import java.time.Duration;
import java.util.Objects;

/**
 * Transport-neutral resource and routing profile requested by a plugin-owned HTTP client.
 */
public record OutboundHttpClientProfile(
        Duration connectTimeout,
        Duration readTimeout,
        OutboundHttpRoute route,
        OutboundHttpRedirectPolicy redirectPolicy,
        OutboundHttpCookiePolicy cookiePolicy,
        int maxConnections,
        int maxConnectionsPerRoute
) {

    public static final int DEFAULT_MAX_CONNECTIONS = 20;
    public static final int DEFAULT_MAX_CONNECTIONS_PER_ROUTE = 10;

    public OutboundHttpClientProfile {
        connectTimeout = positive(connectTimeout, "connectTimeout");
        readTimeout = positive(readTimeout, "readTimeout");
        route = Objects.requireNonNull(route, "route");
        redirectPolicy = Objects.requireNonNull(redirectPolicy, "redirectPolicy");
        cookiePolicy = Objects.requireNonNull(cookiePolicy, "cookiePolicy");
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections must be positive");
        }
        if (maxConnectionsPerRoute < 1 || maxConnectionsPerRoute > maxConnections) {
            throw new IllegalArgumentException(
                    "maxConnectionsPerRoute must be between 1 and maxConnections");
        }
    }

    public static OutboundHttpClientProfile standard(
            Duration connectTimeout,
            Duration readTimeout,
            OutboundHttpRoute route
    ) {
        return new OutboundHttpClientProfile(
                connectTimeout,
                readTimeout,
                route,
                OutboundHttpRedirectPolicy.FOLLOW,
                OutboundHttpCookiePolicy.ENABLED,
                DEFAULT_MAX_CONNECTIONS,
                DEFAULT_MAX_CONNECTIONS_PER_ROUTE);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
