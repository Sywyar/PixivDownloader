package top.sywyar.pixivdownload.plugin.api.schedule.management;

import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;

/**
 * 计划任务对外视图（列表 / 详情）。
 *
 * <p>凭证只投影为中性的 {@code credentialPolicy} 元数据，secret 绝不回显。
 * {@code proxy} 是任务级单独代理（{@code host:port}，非凭证、不含账号口令），可回显供前端编辑；
 * {@code null} = 使用全局代理设置。
 *
 * <p>{@code runState} 是持久化的在途运行态（{@code QUEUED} / {@code RUNNING} /
 * {@code CANCEL_REQUESTED} / {@code null}）；同进程内的协调态只作为即时刷新覆盖。前端据它与正交的
 * {@code lastOutcome}、{@code suspendReason} 和 {@code enabled} 共同决定状态灯。
 *
 * <p>{@code runStartedTime} 仅保留给旧前端的忙碌哨兵；中断恢复以持久化的
 * {@code lastOutcome=INTERRUPTED} 为事实来源。版本化 checkpoint 与凭证 secret 均不暴露给前端。
 *
 */
public record ScheduleTaskView(
        Long id,
        String name,
        boolean enabled,
        String type,
        String sourceType,
        String sourceOwnerPluginId,
        String definitionSchema,
        Integer definitionVersion,
        String paramsJson,
        String presentationJson,
        ScheduledTaskPresentation presentation,
        boolean sourceAvailable,
        String sourceActivationToken,
        String triggerKind,
        Integer intervalMinutes,
        String cronExpr,
        ScheduleCredentialPolicyView credentialPolicy,
        String proxy,
        Long nextRunTime,
        Long lastRunTime,
        String lastStatus,
        String lastMessage,
        Long runStartedTime,
        boolean pendingRetryArmed,
        String lastOutcome,
        String outcomeCode,
        String outcomeMessage,
        String suspendReason,
        String suspendCode,
        String suspendDetailJson,
        String runState,
        int storageVersion,
        long stateVersion,
        long createdTime
) {
}
