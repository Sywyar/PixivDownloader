package top.sywyar.pixivdownload.plugin.api.http.websocket;

import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;

/**
 * 由宿主按一个插件自有配置创建的可关闭出站 WebSocket 传输。
 *
 * <p>{@link #connect(OutboundWebSocketRequest, WebSocket.Listener)} 返回的 future 表示传输握手本身。
 * 取消该 future 必须取消底层握手；握手失败必须保留原始 JDK 传输异常，包括
 * {@code WebSocketHandshakeException} 的响应元数据。
 */
public interface OutboundWebSocketClient extends AutoCloseable {

    /**
     * 启动一次 WebSocket 握手。
     *
     * @param request 传输中立的目标与握手请求头
     * @param listener 归调用方所有的 JDK WebSocket 监听器
     * @return 可取消的传输握手 future
     */
    CompletableFuture<WebSocket> connect(
            OutboundWebSocketRequest request,
            WebSocket.Listener listener
    );

    /**
     * 释放全部传输资源。
     *
     * <p>实现必须保证重复调用安全，在关闭后拒绝新连接、取消待完成握手，并中止活动 socket 以及
     * 关闭后才完成连接的 socket。
     */
    @Override
    void close();
}
