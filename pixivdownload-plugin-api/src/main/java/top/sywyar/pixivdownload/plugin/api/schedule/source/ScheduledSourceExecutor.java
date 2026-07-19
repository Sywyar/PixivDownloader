package top.sywyar.pixivdownload.plugin.api.schedule.source;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;

/** 插件 child context 中的来源发现执行器。宿主仍拥有 tick、claim、并发、pending、取消和检查点提交。 */
public interface ScheduledSourceExecutor {

    String sourceType();

    /** 本来源的 pending 重放策略；默认每轮重放全部 pending。 */
    default ScheduledPendingReplayPolicy pendingReplayPolicy() {
        return ScheduledPendingReplayPolicy.ALWAYS;
    }

    /**
     * 在保存前规范化来源定义。实现必须保持纯函数语义，不读取凭证、不访问网络且不产生外部副作用；
     * 宿主会重新校验所有盖章字段与返回内容。默认实现原样提升草稿为正式定义。
     */
    default ScheduledTaskDefinition prepare(ScheduledTaskDraft draft)
            throws ScheduledExecutionException {
        return draft.toDefinition();
    }

    ScheduledExecutionPlan plan(ScheduledTaskDefinition task) throws ScheduledExecutionException;

    ScheduledDiscoveryResult discover(ScheduledSourceContext context) throws ScheduledExecutionException;
}
