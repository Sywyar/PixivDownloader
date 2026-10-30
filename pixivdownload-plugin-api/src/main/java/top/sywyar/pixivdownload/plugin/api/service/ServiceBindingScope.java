package top.sywyar.pixivdownload.plugin.api.service;

import java.util.function.Supplier;

/**
 * 单轮绑定的服务和资源入口。仅能解析本轮声明的依赖，代理不随提供者变化转接。
 * 依赖失效后新调用拒绝，guard 的迟到辅助通知跳过；在途调用退出后才释放资源。
 * 通过所属 {@link ServiceBinding#close()} 永久撤销绑定；本轮资源由运行时排空后统一关闭。
 */
public interface ServiceBindingScope {
    /**
     * 读取本轮固定的成员；空集合合法，失效后不可读取或调用。
     * @param key 本轮声明的导入
     * @param <T> 服务类型
     * @return 固定双方 publication 的受管成员
     * @throws ServiceAccessException 已失效、未声明或宿主不支持
     */
    default <T> java.util.List<ServiceProvider<T>> findAll(ServiceKey<T> key) {
        throw new ServiceAccessException(ServiceAccessException.Reason.UNSUPPORTED);
    }

    /**
     * @param key 此轮声明的服务依赖
     * @param <T> 服务类型
     * @return 绑定本轮精确 publication 的受控代理
     * @throws ServiceAccessException 本轮已失效或未声明依赖
     */
    <T> T require(ServiceKey<T> key);

    /**
     * 在本轮受管调用中创建并接管资源；工厂抛错须自行撤销尚未返回的部分副作用。
     * @param factory 同步资源工厂，返回可关闭句柄
     * @param <T> 资源类型
     * @return 本轮接管的资源，不应另行关闭
     * @throws ServiceAccessException 本轮已失效
     * @throws top.sywyar.pixivdownload.plugin.api.resource.PluginResourceException 资源准入已关闭
     */
    <T extends AutoCloseable> T acquire(Supplier<T> factory);

    /**
     * 包装交给事件源的辅助回调；失效后的迟到通知跳过，已进入的回调计入真实 drain。
     * @param callback 同步辅助处理，不用于用户命令的成功回执
     * @return 本轮准入控制的包装器
     * @throws ServiceAccessException 本轮已失效
     */
    Runnable guard(Runnable callback);
}
