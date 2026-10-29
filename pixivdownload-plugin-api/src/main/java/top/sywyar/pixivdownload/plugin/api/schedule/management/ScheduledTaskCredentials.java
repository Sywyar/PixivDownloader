package top.sywyar.pixivdownload.plugin.api.schedule.management;

import java.util.Map;

/**
 * 宿主必需的计划凭证管理端口，供已鉴权的管理入口适配既有协议。
 * 绑定和策略动作必须核验当前来源 / 策略 publication，并以任务版本 CAS 持久化；
 * 能力缺席、换代或并发修改明确拒绝，不转交替代者，不回显凭据。
 */
public interface ScheduledTaskCredentials {
    /**
     * 探活并绑定凭据；来源令牌必须属于任务当前 publication，失败不覆盖现有绑定。
     * @param taskId 已存在的任务标识
     * @param secret 待探活的凭据，只用于本次绑定，不得写入日志或返回值
     * @param activationToken 任务当前来源的激活令牌
     * @return 成功持久化绑定后的非敏感任务视图
     * @throws ScheduleManagementException 参数无效、能力不可用、令牌过期或任务状态冲突时抛出
     */
    ScheduleTaskView bindCredential(long taskId, String secret, String activationToken);

    /**
     * 撤销任务当前精确策略绑定，并保留其它计划事实。
     * @param taskId 已存在的任务标识
     * @return 撤销后的非敏感任务视图
     * @throws ScheduleManagementException 任务不存在或当前状态不允许撤销时抛出
     */
    ScheduleTaskView revokeCredential(long taskId);

    /**
     * 对当前策略 publication 执行其声明的账号动作；能力缺席或换代时明确拒绝。
     * @param ownerPluginId 策略所属插件标识
     * @param policyId 当前策略标识
     * @param accountKey 策略内的非敏感账号身份
     * @param actionId 策略声明的账号动作标识
     * @param parameters 动作声明的非敏感参数；不得包含凭据
     * @throws ScheduleManagementException 参数、动作或当前 publication 不满足执行条件时抛出
     */
    void applyCurrentCredentialPolicyAction(
            String ownerPluginId, String policyId, String accountKey,
            String actionId, Map<String, String> parameters);
}
