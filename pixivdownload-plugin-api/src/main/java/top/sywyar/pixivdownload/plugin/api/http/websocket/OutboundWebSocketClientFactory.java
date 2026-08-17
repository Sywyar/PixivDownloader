package top.sywyar.pixivdownload.plugin.api.http.websocket;

/** 用于创建插件自有出站 WebSocket 客户端的稳定宿主能力。 */
@FunctionalInterface
public interface OutboundWebSocketClientFactory {

    /**
     * 按指定配置创建一个由插件负责使用和关闭的客户端。
     *
     * @param profile 连接与路由配置
     * @return 新建的出站 WebSocket 客户端
     */
    OutboundWebSocketClient open(OutboundWebSocketClientProfile profile);
}
