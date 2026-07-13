package top.sywyar.pixivdownload.plugin.api.schedule.source;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;

/**
 * 宿主拥有的有界、可背压作品入口。{@link #submit(ScheduledWork)} 可以阻塞；来源不得先把无限分页积到内存。
 */
@FunctionalInterface
public interface ScheduledWorkSink {

    void submit(ScheduledWork work) throws ScheduledExecutionException;

    /**
     * 记录一件无需调用作品执行器即可确定的本地终态，只允许
     * {@link ScheduledWorkResult.Outcome#ALREADY_COMPLETED} 或
     * {@link ScheduledWorkResult.Outcome#SKIPPED}。新宿主应覆盖本方法，把结果投影到运行队列与统计，且不得把它计入
     * 远端 attempt、{@code WORK_BATCH} cadence 或作品礼貌延迟。
     *
     * <p>默认实现退化为 {@link #submit(ScheduledWork)}，使尚未识别本地完成通道的宿主仍能处理作品；该兼容路径
     * 不提供上述精确计数语义。
     */
    default void completeLocally(ScheduledWork work, ScheduledWorkResult result)
            throws ScheduledExecutionException {
        if (work == null) {
            throw new IllegalArgumentException("locally completed work must not be null");
        }
        if (result == null) {
            throw new IllegalArgumentException("local work result must not be null");
        }
        if (result.outcome() != ScheduledWorkResult.Outcome.ALREADY_COMPLETED
                && result.outcome() != ScheduledWorkResult.Outcome.SKIPPED) {
            throw new IllegalArgumentException("local work result must be already-completed or skipped");
        }
        submit(work);
    }

    /**
     * 等待此前接受的作品全部到达耐久终态。来源只在不同作品类别必须保持严格先后顺序时调用；宿主仍拥有
     * 实际排空、失败传播、取消与背压。默认无动作以保持旧宿主二进制兼容。
     */
    default void drain() throws ScheduledExecutionException {
    }
}
