package top.sywyar.pixivdownload.schedule.dto;

import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialTaskPresentation;

/** 计划任务所绑定凭证策略的非敏感、publication-scoped 对外投影。 */
public record ScheduleCredentialPolicyView(
        String ownerPluginId,
        String policyId,
        String accountKey,
        boolean bound,
        boolean available,
        Long publicationId,
        String statusCode,
        Long acknowledgedEventTime
) {

    public static ScheduleCredentialPolicyView unavailable(
            String ownerPluginId,
            String policyId,
            String accountKey,
            boolean bound) {
        return new ScheduleCredentialPolicyView(
                ownerPluginId, policyId, accountKey, bound,
                false, null, null, null);
    }

    public static ScheduleCredentialPolicyView available(
            String ownerPluginId,
            String policyId,
            String accountKey,
            boolean bound,
            long publicationId,
            ScheduledCredentialTaskPresentation presentation) {
        ScheduledCredentialTaskPresentation safe = presentation == null
                ? ScheduledCredentialTaskPresentation.empty()
                : presentation;
        return new ScheduleCredentialPolicyView(
                ownerPluginId, policyId, accountKey, bound,
                true, publicationId, safe.statusCode(), safe.acknowledgedEventTime());
    }
}
