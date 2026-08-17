package top.sywyar.pixivdownload.plugin.api.schedule.execution;

/** 宿主控制的协作式取消信号。插件的分页、礼貌延迟和阻塞执行必须定期检查它。 */
@FunctionalInterface
public interface ScheduledCancellation {

    /**
     * 判断取消状态请求值是否满足条件。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean isCancellationRequested();

    /**
     * 执行对应操作。
     *
     * @throws ScheduledExecutionException 执行失败时抛出
     */
    default void throwIfCancellationRequested() throws ScheduledExecutionException {
        if (isCancellationRequested()) {
            throw ScheduledExecutionException.cancelled();
        }
    }
}
