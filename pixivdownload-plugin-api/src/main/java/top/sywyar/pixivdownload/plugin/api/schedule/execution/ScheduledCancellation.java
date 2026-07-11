package top.sywyar.pixivdownload.plugin.api.schedule.execution;

/** 宿主控制的协作式取消信号。插件的分页、礼貌延迟和阻塞执行必须定期检查它。 */
@FunctionalInterface
public interface ScheduledCancellation {

    boolean isCancellationRequested();

    default void throwIfCancellationRequested() throws ScheduledExecutionException {
        if (isCancellationRequested()) {
            throw ScheduledExecutionException.cancelled();
        }
    }
}
