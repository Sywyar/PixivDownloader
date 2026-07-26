package top.sywyar.pixivdownload.plugin.api.task;

/**
 * 某插件一个精确运行期任务代际停止接收后的归零凭据。
 *
 * <p>凭据只暴露宿主标量身份与计数状态，不持有插件 Bean、任务 delegate、classloader 或子 context。
 */
public interface PluginRuntimeTaskDrain {

    /** 宿主固化的插件 owner。 */
    String ownerPluginId();

    /** 当前 owner 的任务 admission 代际；每次成功 resume 后递增。 */
    long generation();

    /** 当前仍需清退的包装器数量。 */
    int activeCount();

    /** 当前代际是否已经归零。 */
    boolean isDrained();

    /**
     * 等到绝对 {@link System#nanoTime()} 截止值；中断时恢复中断标志并返回 {@code false}。
     */
    boolean awaitDrained(long deadlineNanos);

    /**
     * 无截止时间等待；中断时恢复中断标志并返回 {@code false}。
     */
    boolean awaitDrained();
}
