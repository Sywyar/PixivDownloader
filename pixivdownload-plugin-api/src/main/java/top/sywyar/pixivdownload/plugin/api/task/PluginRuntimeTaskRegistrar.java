package top.sywyar.pixivdownload.plugin.api.task;

/**
 * 当前插件专属的后台任务登记入口。
 *
 * <p>宿主在创建插件子 context 时按可信插件身份提供本接口。插件不自报 plugin id、package id、
 * generation 或 publication；每个返回的包装器都自动归属于当前 owner。
 */
public interface PluginRuntimeTaskRegistrar {

    /**
     * 登记最多执行一次的任务。执行结束或主动取消后自动摘除；quiesce 在排队阶段清除 delegate，
     * 父执行器随后运行空包装器时完成精确归零。
     *
     * @param delegate 插件业务任务
     * @return 必须提交给执行器的宿主包装器
     * @throws PluginRuntimeTaskRejectedException 当前 owner 已停止接收新任务
     */
    PluginRuntimeTask registerOneShot(Runnable delegate);

    /**
     * 登记可被调度器反复调用的周期任务。调用方必须把调度器返回的 {@code Future} 立即传给
     * {@link PluginRuntimeTask#bindCancellation(java.util.concurrent.Future)}；若调度器明确拒绝提交且不会返回
     * 句柄，调用方必须以 {@link PluginRuntimeTask#discardUnsubmitted()} 终结该包装器。
     *
     * @param delegate 插件业务任务
     * @return 必须提交给调度器的宿主包装器
     * @throws PluginRuntimeTaskRejectedException 当前 owner 已停止接收新任务
     */
    PluginRuntimeTask registerPeriodic(Runnable delegate);

    /**
     * 当前插件是否仍允许登记新后台任务。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean acceptsNewTasks();
}
