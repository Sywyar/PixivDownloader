package top.sywyar.pixivdownload.plugin.runtime.lifecycle;

/**
 * 插件运行期是否仍接收新调用的中性准入视图。
 *
 * <p>运行时注册中心只消费这一项只读判断，不依赖宿主的状态机实现、请求网关或生命周期编排服务。
 */
@FunctionalInterface
public interface PluginLifecycleAdmission {

    /**
     * 返回指定插件当前是否允许取得新的运行期租约。未知、静默或已停止的插件应返回 {@code false}。
     */
    boolean acceptsNewRequests(String pluginId);
}
