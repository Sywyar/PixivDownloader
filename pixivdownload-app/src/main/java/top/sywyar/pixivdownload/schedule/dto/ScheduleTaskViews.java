package top.sywyar.pixivdownload.schedule.dto;

import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.management.ScheduleTaskView;
import top.sywyar.pixivdownload.plugin.api.schedule.management.ScheduleCredentialPolicyView;

/** 从宿主持久化事实生成不含凭据的管理视图。 */
public final class ScheduleTaskViews {
    private ScheduleTaskViews() {}

    public static ScheduleTaskView of(
            ScheduledTask t,
            String runState,
            ScheduledTaskPresentation presentation,
            boolean sourceAvailable,
            String sourceActivationToken,
            ScheduleCredentialPolicyView credentialPolicy) {
        String effectiveRunState = runState != null
                ? runState
                : t.runState() == null ? null : t.runState().name();
        ScheduleCredentialPolicyView policy = credentialPolicy == null
                ? ScheduleCredentialPolicyView.unavailable(
                        t.credentialPolicyOwnerPluginId(), t.credentialPolicyId(),
                        t.credentialAccountKey(),
                        t.credentialPolicyOwnerPluginId() != null
                                && t.credentialSecretReference() != null)
                : credentialPolicy;
        return new ScheduleTaskView(
                t.id(), t.name(), t.enabled(),
                t.sourceType(), t.sourceOwnerPluginId(), t.definitionSchema(), t.definitionVersion(),
                t.definitionJson(), t.presentationJson(), presentation,
                sourceAvailable, sourceActivationToken,
                t.triggerKind(), t.intervalMinutes(), t.cronExpr(),
                policy,
                t.proxySnapshot(),
                t.nextRunTime(), t.lastRunTime(),
                t.lastOutcome() == null ? null : t.lastOutcome().name(),
                t.outcomeCode(), t.outcomeMessage(),
                t.suspendReason() == null ? null : t.suspendReason().name(),
                t.suspendCode(), t.suspendDetailJson(),
                effectiveRunState, t.storageVersion(), t.stateVersion(), t.createdTime());
    }

}
