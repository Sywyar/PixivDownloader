package top.sywyar.pixivdownload.schedule.dto;

import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
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
    private static final String STATUS_PAUSED = "PAUSED";

    public static ScheduleTaskView of(
            ScheduledTask t,
            String runState,
            String legacyType,
            ScheduledTaskPresentation presentation,
            boolean sourceAvailable,
            String sourceActivationToken,
            ScheduleCredentialPolicyView credentialPolicy) {
        String effectiveRunState = runState != null
                ? runState
                : t.runState() == null ? null : t.runState().name();
        String compatibleType = legacyType == null || legacyType.isBlank()
                ? t.sourceType() : legacyType;
        ScheduleCredentialPolicyView policy = credentialPolicy == null
                ? ScheduleCredentialPolicyView.unavailable(
                        t.credentialPolicyOwnerPluginId(), t.credentialPolicyId(),
                        t.credentialAccountKey(),
                        t.credentialPolicyOwnerPluginId() != null
                                && t.credentialSecretReference() != null)
                : credentialPolicy;
        String lastStatus = compatibilityLastStatus(t, policy.statusCode());
        String lastMessage = t.suspendReason() == null ? t.outcomeMessage() : t.suspendDetailJson();
        Long runStartedTime = t.runState() == null
                ? null
                : t.lastRunTime() == null ? 1L : t.lastRunTime();
        return new ScheduleTaskView(
                t.id(), t.name(), t.enabled(), compatibleType,
                t.sourceType(), t.sourceOwnerPluginId(), t.definitionSchema(), t.definitionVersion(),
                t.definitionJson(), t.presentationJson(), presentation,
                sourceAvailable, sourceActivationToken,
                t.triggerKind(), t.intervalMinutes(), t.cronExpr(),
                policy,
                t.proxySnapshot(),
                t.nextRunTime(), t.lastRunTime(), lastStatus, lastMessage,
                runStartedTime,
                false,
                t.lastOutcome() == null ? null : t.lastOutcome().name(),
                t.outcomeCode(), t.outcomeMessage(),
                t.suspendReason() == null ? null : t.suspendReason().name(),
                t.suspendCode(), t.suspendDetailJson(),
                effectiveRunState, t.storageVersion(), t.stateVersion(), t.createdTime());
    }

    private static String compatibilityLastStatus(
            ScheduledTask task,
            String policyStatusCode) {
        ScheduleSuspendReason reason = task.suspendReason();
        if (reason != null) {
            return switch (reason) {
                case MANUAL -> STATUS_PAUSED;
                case CREDENTIAL, POLICY -> policyStatusCode == null
                        ? reason.name() : policyStatusCode;
                case SOURCE_UNAVAILABLE, EXECUTOR_UNAVAILABLE, QUIESCED, MIGRATION_ERROR ->
                        reason.name();
            };
        }
        ScheduleLastOutcome outcome = task.lastOutcome();
        return outcome == null || outcome == ScheduleLastOutcome.NEVER ? null : outcome.name();
    }
}
