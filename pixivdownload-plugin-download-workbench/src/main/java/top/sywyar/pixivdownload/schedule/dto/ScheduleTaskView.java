package top.sywyar.pixivdownload.schedule.dto;

import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;

/**
 * 计划任务对外视图（列表 / 详情）。
 *
 * <p><b>不含</b> {@code cookieSnapshot}：只用 {@code cookieBound} 布尔位告知前端是否已绑定凭证，
 * 凭证本身绝不回显。{@code proxy} 是任务级单独代理（{@code host:port}，非凭证、不含账号口令），
 * 可回显供前端「指定单独的 代理/cookie」弹窗预填编辑；{@code null} = 使用全局代理设置。
 *
 * <p>{@code runState} 是持久化的在途运行态（{@code QUEUED} / {@code RUNNING} /
 * {@code CANCEL_REQUESTED} / {@code null}）；同进程内的协调态只作为即时刷新覆盖。前端据它与正交的
 * {@code lastOutcome}、{@code suspendReason} 和 {@code enabled} 共同决定状态灯。
 *
 * <p>{@code runStartedTime} 仅保留给旧前端的忙碌哨兵；中断恢复以持久化的
 * {@code lastOutcome=INTERRUPTED} 为事实来源。版本化 checkpoint 与凭证 secret 均不暴露给前端。
 *
 * <p>{@code accountId} 是非敏感 Pixiv userId（过度访问暂停按它分组）；{@code ackWarningTime} /
 * {@code pendingRetryArmed} 是非凭证运行态，可透出供前端展示账号级暂停与重试武装状态。
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
        String cookieMode,
        boolean cookieBound,
        String proxy,
        Long nextRunTime,
        Long lastRunTime,
        String lastStatus,
        String lastMessage,
        Long runStartedTime,
        String accountId,
        Long ackWarningTime,
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
    private static final String COOKIE_BOUND = "bound";
    private static final String COOKIE_RESTRICTED = "restricted";
    private static final String STATUS_PAUSED = "PAUSED";
    private static final String STATUS_AUTH_EXPIRED = "AUTH_EXPIRED";
    private static final String STATUS_OVERUSE_PAUSED = "OVERUSE_PAUSED";

    public static ScheduleTaskView of(
            ScheduledTask t,
            String runState,
            PixivSchedulePersistenceCodec persistenceCodec) {
        return of(
                t,
                runState,
                persistenceCodec,
                ScheduledTaskPresentation.empty(),
                false,
                null);
    }

    public static ScheduleTaskView of(
            ScheduledTask t,
            String runState,
            PixivSchedulePersistenceCodec persistenceCodec,
            ScheduledTaskPresentation presentation,
            boolean sourceAvailable,
            String sourceActivationToken) {
        String effectiveRunState = runState != null
                ? runState
                : t.runState() == null ? null : t.runState().name();
        String legacyType = PixivSchedulePersistenceCodec.legacySourceAliases().entrySet().stream()
                .filter(entry -> entry.getValue().equals(t.sourceType()))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElse(t.sourceType());
        boolean credentialBound = t.credentialPolicyOwnerPluginId() != null
                && t.credentialSecretReference() != null;
        String lastStatus = compatibilityLastStatus(t);
        String lastMessage = t.suspendReason() == null ? t.outcomeMessage() : t.suspendDetailJson();
        Long runStartedTime = t.runState() == null
                ? null
                : t.lastRunTime() == null ? 1L : t.lastRunTime();
        Long acknowledgedWarningTime = null;
        if (t.credentialPolicyStateJson() != null) {
            try {
                acknowledgedWarningTime = persistenceCodec.decodeAcknowledgedWarningTime(
                        t.credentialPolicyStateJson());
            } catch (IllegalArgumentException ignored) {
                // 非 Pixiv/旧损坏策略状态不进入兼容视图；正交机器字段仍原样返回。
            }
        }
        return new ScheduleTaskView(
                t.id(), t.name(), t.enabled(), legacyType,
                t.sourceType(), t.sourceOwnerPluginId(), t.definitionSchema(), t.definitionVersion(),
                t.definitionJson(), t.presentationJson(), presentation,
                sourceAvailable, sourceActivationToken,
                t.triggerKind(), t.intervalMinutes(), t.cronExpr(),
                credentialBound ? COOKIE_BOUND : COOKIE_RESTRICTED,
                credentialBound,
                t.proxySnapshot(),
                t.nextRunTime(), t.lastRunTime(), lastStatus, lastMessage,
                runStartedTime, t.credentialAccountKey(), acknowledgedWarningTime,
                false,
                t.lastOutcome() == null ? null : t.lastOutcome().name(),
                t.outcomeCode(), t.outcomeMessage(),
                t.suspendReason() == null ? null : t.suspendReason().name(),
                t.suspendCode(), t.suspendDetailJson(),
                effectiveRunState, t.storageVersion(), t.stateVersion(), t.createdTime());
    }

    private static String compatibilityLastStatus(ScheduledTask task) {
        ScheduleSuspendReason reason = task.suspendReason();
        if (reason != null) {
            return switch (reason) {
                case MANUAL -> STATUS_PAUSED;
                case CREDENTIAL -> STATUS_AUTH_EXPIRED;
                case POLICY -> STATUS_OVERUSE_PAUSED;
                case SOURCE_UNAVAILABLE, EXECUTOR_UNAVAILABLE, QUIESCED, MIGRATION_ERROR ->
                        reason.name();
            };
        }
        ScheduleLastOutcome outcome = task.lastOutcome();
        return outcome == null || outcome == ScheduleLastOutcome.NEVER ? null : outcome.name();
    }
}
