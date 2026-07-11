package top.sywyar.pixivdownload.plugin.api.schedule.work;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;

/** 插件 child context 中的同步作品执行器。手动队列与计划任务应复用其底层 blocking 执行接缝。 */
public interface ScheduledWorkExecutor {

    String workType();

    ScheduledWorkResult execute(ScheduledWork work, ScheduledWorkContext context)
            throws ScheduledExecutionException;
}
