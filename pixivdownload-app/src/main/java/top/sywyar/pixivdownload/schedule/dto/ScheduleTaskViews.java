package top.sywyar.pixivdownload.schedule.dto;

import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.management.ScheduleTaskView;
import top.sywyar.pixivdownload.plugin.api.schedule.management.ScheduleCredentialPolicyView;

/** 从宿主持久化事实生成不含凭据的管理视图。 */
public final class ScheduleTaskViews {
    private ScheduleTaskViews() {}
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
                case MANUAL, USER_ACTION_REQUIRED -> STATUS_PAUSED;
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
