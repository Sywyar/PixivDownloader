package top.sywyar.pixivdownload.plugin.api.schedule.source;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionContext;

/** 来源执行器的受控发现上下文。 */
public interface ScheduledSourceContext extends ScheduledExecutionContext {

    /** 尚无检查点时返回 {@code null}。 */
    ScheduledCheckpoint checkpoint();

    ScheduledWorkSink workSink();
}
