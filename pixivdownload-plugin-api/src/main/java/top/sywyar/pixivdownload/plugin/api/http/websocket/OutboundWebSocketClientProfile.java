package top.sywyar.pixivdownload.plugin.api.http.websocket;

import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;

import java.time.Duration;
import java.util.Objects;

/**
 * 单个插件自有 WebSocket 客户端的传输中立连接与路由配置。
 *
 * @param connectTimeout 完成握手的超时时间
 * @param route 出站代理路由
 */
public record OutboundWebSocketClientProfile(
        Duration connectTimeout,
        OutboundHttpRoute route
) {

    /**
     * 校验连接超时与路由配置。
     *
     * @param connectTimeout 连接超时
     * @param route 路由
     */
    public OutboundWebSocketClientProfile {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        route = Objects.requireNonNull(route, "route");
    }
}
