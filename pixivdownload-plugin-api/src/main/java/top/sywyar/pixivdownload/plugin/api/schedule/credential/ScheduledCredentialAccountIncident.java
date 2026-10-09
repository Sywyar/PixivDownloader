package top.sywyar.pixivdownload.plugin.api.schedule.credential;

import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;

import java.util.List;

/**
 * 账号级策略挂起持久化后，用于生成安全通知投影的纯值事件。
 *
 * @param accountKey 不含凭证材料的账号键
 * @param reasonCode 账号挂起原因机器码
 * @param evidence 可安全展示的挂起证据；null 视为空证据
 * @param occurredAt 事件发生时间，不能为负数
 * @param tasks 受影响任务的非空快照列表，任务标识不能重复
 */
public record ScheduledCredentialAccountIncident(
        String accountKey,
        String reasonCode,
        ScheduledGuardEvidence evidence,
        long occurredAt,
        List<ScheduledCredentialTaskSnapshot> tasks
) {

    /**
     * 创建 {@code ScheduledCredentialAccountIncident} 实例。
     *
     * @param accountKey 账号键
     * @param reasonCode 原因代码
     * @param evidence 证据
     * @param occurredAt {@code occurredAt} 对应的值
     * @param tasks 任务列表
     */
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
