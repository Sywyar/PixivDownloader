package top.sywyar.pixivdownload.core.schedule;

import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;

/**
 * 插件中性的计划任务持久化投影。
 *
 * <p>{@code type}/{@code params_json} 的物理列名为兼容已发布数据库而保留；Java 语义分别是 canonical
 * {@link #sourceType()} 与不透明 {@link #definitionJson()}。凭证 secret 不在本 record 中，只能经
 * {@link ScheduledTaskStore#findCredentialSecret(long, String, String)} 的专用裸标量入口读取。
 * 所有时间列均为 Unix epoch 毫秒。
 *
 * @param id 任务持久化标识
 * @param name 任务名称
 * @param enabled 是否允许自动调度
 * @param sourceType 规范化的来源类型
 * @param sourceOwnerPluginId 任务来源所属功能插件标识
 * @param definitionSchema 来源任务定义的格式标识
 * @param definitionVersion 来源任务定义的格式版本
 * @param definitionJson 来源拥有的不透明任务定义 JSON
 * @param presentationJson 插件缺席时仍可使用的安全展示快照 JSON
 * @param triggerKind 间隔或 Cron 触发方式标识
 * @param intervalMinutes 间隔触发的分钟数
 * @param cronExpr Cron 触发表达式
 * @param proxySnapshot 任务保存的代理配置快照
 * @param nextRunTime 下次计划运行时间
 * @param lastRunTime 最近一次运行时间
 * @param checkpointSchema 已提交检查点的格式标识
 * @param checkpointVersion 已提交检查点的格式版本
 * @param checkpointJson 已提交检查点的载荷 JSON
 * @param storageVersion 宿主持久化表示的版本
 * @param runState 当前运行状态
 * @param runClaimToken 本次执行的认领身份令牌
 * @param lastOutcome 最近一次执行结果类别
 * @param outcomeCode 最近一次执行结果的机器码
 * @param outcomeMessage 最近一次执行结果的安全说明
 * @param suspendReason 当前挂起原因分类
 * @param suspendCode 挂起原因机器码
 * @param suspendDetailJson 挂起详情的安全 JSON
 * @param stateVersion 用于状态 CAS 更新的版本号
 * @param credentialPolicyOwnerPluginId 凭证策略所属功能插件标识
 * @param credentialPolicyId 任务绑定的凭证策略标识
 * @param credentialAccountKey 不含凭证 secret 的账号键
 * @param credentialPolicyStateJson 凭证策略拥有的安全状态 JSON
 * @param credentialSecretReference 凭证 secret 的存储引用，非凭证内容
 * @param createdTime 任务创建时间
 */
public record ScheduledTask(
        Long id,
        String name,
        boolean enabled,
        String sourceType,
        String sourceOwnerPluginId,
        String definitionSchema,
        Integer definitionVersion,
        String definitionJson,
        String presentationJson,
        String triggerKind,
        Integer intervalMinutes,
        String cronExpr,
        String proxySnapshot,
        Long nextRunTime,
        Long lastRunTime,
        String checkpointSchema,
        Integer checkpointVersion,
        String checkpointJson,
        int storageVersion,
        ScheduleRunState runState,
        String runClaimToken,
        ScheduleLastOutcome lastOutcome,
        String outcomeCode,
        String outcomeMessage,
        ScheduleSuspendReason suspendReason,
        String suspendCode,
        String suspendDetailJson,
        long stateVersion,
        String credentialPolicyOwnerPluginId,
        String credentialPolicyId,
        String credentialAccountKey,
        String credentialPolicyStateJson,
        String credentialSecretReference,
        long createdTime
) {
    /**
     * 旧版兼容值。
     */
    public static final int LEGACY_STORAGE_VERSION = 0;
    /**
     * 当前使用的值。
     */
    public static final int CURRENT_STORAGE_VERSION = 1;

    /**
     * 间隔触发方式标识。
     */
    public static final String TRIGGER_INTERVAL = "interval";
    /**
     * Cron 表达式触发方式标识。
     */
    public static final String TRIGGER_CRON = "cron";
}
