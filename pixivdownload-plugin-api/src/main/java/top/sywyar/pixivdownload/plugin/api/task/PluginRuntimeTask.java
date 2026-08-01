package top.sywyar.pixivdownload.plugin.api.task;

import java.util.concurrent.Future;

/**
 * 可提交给宿主执行器或调度器的插件运行期任务包装器。
 *
 * <p>包装器实现由宿主父加载器提供。插件只把业务 {@link Runnable} 交给 owner-scoped
 * {@link PluginRuntimeTaskRegistrar}，再提交返回的包装器；宿主可在插件 quiesce 时清除业务 delegate，
 * 等待已经运行的调用退出，并取消已绑定的调度句柄。
 */
public interface PluginRuntimeTask extends Runnable {

    /**
     * 绑定宿主执行器返回的取消句柄。周期任务必须在调度调用返回后立即绑定；若 quiesce 已先发生，
     * 实现会立即尝试取消这个迟到句柄。
     *
     * @param cancellation 执行器或调度器返回的精确任务句柄
     */
    void bindCancellation(Future<?> cancellation);

    /**
     * 主动撤销当前包装器。用于正常连接关闭或插件自己的局部清理；重复调用幂等。
     *
     * <p>若取消句柄尚未绑定，包装器会保持活动并等待迟到句柄，避免取消失败后提前让生命周期 drain
     * 归零。只有调用方能够确认包装器从未提交给执行器时，才可改用 {@link #discardUnsubmitted()}。
     */
    void cancel();

    /**
     * 终结一个确定未提交给执行器、也不会再绑定取消句柄的包装器。
     *
     * <p>本方法只用于执行器或调度器明确拒绝提交的失败路径；已绑定句柄或已经开始运行时调用会失败，
     * 防止把仍可能运行的任务伪装成已归零。
     */
    void discardUnsubmitted();
}
