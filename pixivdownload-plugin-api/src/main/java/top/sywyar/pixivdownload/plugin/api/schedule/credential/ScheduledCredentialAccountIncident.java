package top.sywyar.pixivdownload.plugin.api.schedule.credential;

import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;

import java.util.List;

/** 账号级策略挂起持久化后，用于生成安全通知投影的纯值事件。 */
public record ScheduledCredentialAccountIncident(
        String accountKey,
        String reasonCode,
        ScheduledGuardEvidence evidence,
        long occurredAt,
        List<ScheduledCredentialTaskSnapshot> tasks
) {

    public ScheduledCredentialAccountIncident {
        accountKey = ScheduledCredentialAccountActionRequest.validateAccountKey(accountKey);
        reasonCode = ScheduledCredentialAccountActionRequest.validateMachineCode(
                reasonCode, "credential account incident reason code");
        evidence = evidence == null ? ScheduledGuardEvidence.empty() : evidence;
        if (occurredAt < 0) {
            throw new IllegalArgumentException(
                    "credential account incident time must not be negative");
        }
        tasks = ScheduledCredentialAccountActionRequest.validateTasks(
                tasks, "credential account incident");
    }
}
