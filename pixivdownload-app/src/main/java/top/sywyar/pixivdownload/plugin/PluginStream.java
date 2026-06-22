package top.sywyar.pixivdownload.plugin;

/**
 * 一条由插件拥有的长连接服务端推流（当前唯一实现为下载进度 SSE 流），注册进 {@link PluginStreamRegistry}
 * 以便在拥有它的插件被 quiesce / 卸载时<b>主动关闭</b>并通知客户端「插件不可用」。
 *
 * <p><b>实现约束（避免泄漏）</b>：实现 / lambda 只能捕获传输句柄（如 {@code SseEmitter}）与本地化文案等
 * <b>核心 / JDK 值</b>，<b>绝不</b>捕获外置插件 Bean / classloader / 子 context——注册中心在关闭后即丢弃这些
 * 回调，使连接不成为插件 classloader 的泄漏点。
 */
@FunctionalInterface
public interface PluginStream {

    /** 通知客户端拥有它的插件已不可用并关闭该流。必须吞掉自身异常、绝不上抛（由注册中心逐条隔离）。 */
    void closeUnavailable();
}
