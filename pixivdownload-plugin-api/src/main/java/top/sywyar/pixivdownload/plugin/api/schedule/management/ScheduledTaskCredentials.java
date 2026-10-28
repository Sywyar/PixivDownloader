package top.sywyar.pixivdownload.plugin.api.schedule.management;


import java.util.Map;

/**
 * 宿主必需的计划凭证管理端口，供已鉴权的管理入口适配既有协议。
 * 绑定和策略动作必须核验当前来源 / 策略 publication，并以任务版本 CAS 持久化；
 * 能力缺席、换代或并发修改明确拒绝，不转交替代者，不回显凭据。
 */
public interface ScheduledTaskCredentials {
    /** 探活并绑定凭据；activationToken 必须属于任务当前来源。 */
    ScheduleTaskView bindCredential(long taskId, String secret, String activationToken);

    /** 撤销任务当前精确策略绑定，并保留其它计划事实。 */
    ScheduleTaskView revokeCredential(long taskId);

    /** 对当前策略 publication 执行其声明的账号动作。 */
    void applyCurrentCredentialPolicyAction(
            String ownerPluginId, String policyId, String accountKey,
            String actionId, Map<String, String> parameters);
}
