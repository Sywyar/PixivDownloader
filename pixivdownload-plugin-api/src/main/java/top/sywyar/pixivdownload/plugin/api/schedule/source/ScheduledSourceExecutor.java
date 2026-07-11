package top.sywyar.pixivdownload.plugin.api.schedule.source;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;

/** 插件 child context 中的来源发现执行器。宿主仍拥有 tick、claim、并发、pending、取消和检查点提交。 */
public interface ScheduledSourceExecutor {

    String sourceType();

    ScheduledExecutionPlan plan(ScheduledTaskDefinition task) throws ScheduledExecutionException;

    ScheduledDiscoveryResult discover(ScheduledSourceContext context) throws ScheduledExecutionException;
}
