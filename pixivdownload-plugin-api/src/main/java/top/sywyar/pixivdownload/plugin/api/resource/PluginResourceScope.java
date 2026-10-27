package top.sywyar.pixivdownload.plugin.api.resource;

import java.util.function.Supplier;

/**
 * 一组订阅、监听器或临时注册的共同所有者，只用于插件本地资源，不属于跨服务纯值合同。
 *
 * <p>局部关闭先禁止新操作；运行中的受管工厂或回调结束前关闭返回 {@code BUSY}，调用方稍后重试，
 * 或交给宿主停用清理。成功关闭的资源不重复关闭；关闭失败项保留供重试，因此 disposer 必须幂等。
 * 局部关闭不等待任意外部业务调用，调用方须确保这些调用已停止使用该作用域的资源。
 */
public interface PluginResourceScope extends AutoCloseable {

    /**
     * 在受管调用中创建并接管资源。关闭准入后不执行工厂，避免迟到注册产生无人接管的资源。
     * 已进入的工厂会完成交接；工厂自身抛错时，工厂负责撤销尚未返回的部分副作用。
     *
     * @param factory 同步资源工厂；返回值不能为空
     * @param <T> 资源类型
     * @return 由本作用域管理的资源，不应另行关闭
     * @throws PluginResourceException 当前作用域或 owner 已关闭准入
     */
    <T extends AutoCloseable> T acquire(Supplier<T> factory);

    /**
     * 包装辅助事件回调。撤回后迟到调用跳过；已经进入的回调占用现有任务登记器的真实 drain。
     * 回调异常原样传播，不自动重试。包装器在作用域撤回时清除插件 delegate。
     *
     * @param callback 同步辅助事件处理，不用于用户命令或任务提交的成功回执
     * @return 应交给事件源的宿主包装器
     * @throws PluginResourceException 当前作用域或 owner 已关闭准入
     */
    Runnable guard(Runnable callback);

    /**
     * 禁止新操作并逆序释放资源。成功后幂等；普通关闭失败不阻止其它资源释放。
     *
     * @throws PluginResourceException 在途操作未结束、另一关闭正在执行或资源关闭失败
     */
    @Override
    void close();
}
