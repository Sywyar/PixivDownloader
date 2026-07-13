package top.sywyar.pixivdownload.tts;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;

/**
 * Edge TTS WebSocket 连接边界。
 *
 * <p>客户端只管理协议与异步会话生命周期；实际 JDK WebSocket 建连由此边界提供，
 * 便于在不访问外网的情况下确定性验证建连、停止与迟到回调竞态。
 */
@FunctionalInterface
public interface EdgeTtsWebSocketConnector {

    CompletableFuture<WebSocket> connect(URI uri, WebSocket.Listener listener);
}
