package top.sywyar.pixivdownload.plugin.api.schedule.source;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;

/** 来源执行器的受控发现上下文。 */
public interface ScheduledSourceContext extends ScheduledExecutionContext {

    /**
     * 尚无检查点时返回 {@code null}。
     *
     * @return 方法返回的 {@code ScheduledCheckpoint} 实例
     */
    ScheduledCheckpoint checkpoint();

    /**
     * 返回对应值。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    ScheduledWorkSink workSink();

    /**
     * 当前任务是否存在指定 {@code workType + id} 作品的耐久 pending。
     *
     * @param key 键
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean isPending(ScheduledWorkKey key);
}
