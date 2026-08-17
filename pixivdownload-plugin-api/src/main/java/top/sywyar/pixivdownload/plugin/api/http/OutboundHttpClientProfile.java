package top.sywyar.pixivdownload.plugin.api.http;

import java.time.Duration;
import java.util.Objects;

/**
 * 插件自有 HTTP 客户端请求的传输中立资源与路由配置。
 *
 * @param connectTimeout 建立连接的超时时间
 * @param readTimeout 读取响应的超时时间
 * @param route 出站代理路由
 * @param redirectPolicy 重定向策略
 * @param cookiePolicy Cookie 存储策略
 * @param maxConnections 客户端最大连接数
 * @param maxConnectionsPerRoute 单路由最大连接数
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

    /** 默认客户端最大连接数。 */
    public static final int DEFAULT_MAX_CONNECTIONS = 20;
    /** 默认单路由最大连接数。 */
    public static final int DEFAULT_MAX_CONNECTIONS_PER_ROUTE = 10;

    /**
     * 校验并规范化传输资源与路由配置。
     *
     * @param connectTimeout 连接超时
     * @param readTimeout 读取超时
     * @param route 路由
     * @param redirectPolicy 重定向策略
     * @param cookiePolicy Cookie 策略
     * @param maxConnections 最大连接数
     * @param maxConnectionsPerRoute 每条路由的最大连接数
     */
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

    /**
     * 创建允许重定向和客户端私有 Cookie 存储的标准配置。
     *
     * @param connectTimeout 建立连接的超时时间
     * @param readTimeout 读取响应的超时时间
     * @param route 出站代理路由
     * @return 标准客户端配置
     */
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

    /**
     * 创建带调用方凭证的无状态请求配置，不保存 Cookie，也不跟随可能把凭证带到其它目标的重定向。
     *
     * @param connectTimeout 建立连接的超时时间
     * @param readTimeout 读取响应的超时时间
     * @param route 出站代理路由
     * @return 凭证请求客户端配置
     */
    public static OutboundHttpClientProfile credentialed(
            Duration connectTimeout,
            Duration readTimeout,
            OutboundHttpRoute route
    ) {
        return new OutboundHttpClientProfile(
                connectTimeout,
                readTimeout,
                route,
                OutboundHttpRedirectPolicy.NEVER,
                OutboundHttpCookiePolicy.DISABLED,
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
