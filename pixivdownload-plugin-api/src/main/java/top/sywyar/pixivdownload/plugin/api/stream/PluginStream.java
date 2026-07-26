package top.sywyar.pixivdownload.plugin.api.stream;

/**
 * 一条由插件拥有、在插件停止服务时需要主动关闭的长连接服务端推流。
 *
 * <p>实现应尽力通知客户端当前插件已不可用并完成传输句柄。关闭失败可以抛出异常，宿主会继续尝试其它流，
 * 并保留失败回调供生命周期重试。
 */
@FunctionalInterface
public interface PluginStream {

    /**
     * 通知客户端当前插件已不可用并关闭该流。
     */
    void closeUnavailable();
}
