package top.sywyar.pixivdownload.plugin.api.schedule.guard;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionContext;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;

/** Guard 在固定检查点收到的只读上下文。失败检查点以安全投影代替插件 {@link Throwable}。 */
public interface ScheduledGuardContext extends ScheduledExecutionContext {

    ScheduledGuardPoint point();

    long attemptedWorkCount();

    /** 仅 {@link ScheduledGuardPoint#RUN_FAILURE} 非空。 */
    ScheduledFailure failure();
}
