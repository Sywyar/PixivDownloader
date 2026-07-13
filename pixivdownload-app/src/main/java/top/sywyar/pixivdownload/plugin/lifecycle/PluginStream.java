package top.sywyar.pixivdownload.plugin.lifecycle;


/**
 * 一条由插件拥有的长连接服务端推流（当前唯一实现为下载进度 SSE 流），注册进 {@link PluginStreamRegistry}
 * 以便在拥有它的插件被 quiesce / 卸载时<b>主动关闭</b>并通知客户端「插件不可用」。
 *
 * <p>实现 / lambda 可以封装 child controller 的精确连接关闭逻辑；因此注册中心只有在 callback 成功后才能移除它。
 * callback 失败时生命周期会保留 child context 并重试，不能把失败回调丢失后继续卸载。
 */
@FunctionalInterface
public interface PluginStream {

    /**
     * 通知客户端拥有它的插件已不可用并关闭该流。实现应自行 best-effort 完成传输句柄；若仍失败可抛出，
     * 注册中心会继续尝试其它流、保留失败 callback 供生命周期重试，并延后重抛首个失败。
     */
    void closeUnavailable();
}
