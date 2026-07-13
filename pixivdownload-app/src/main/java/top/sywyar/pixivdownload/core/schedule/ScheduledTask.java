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
        Long credentialUpdatedTime,
        long createdTime
) {
    public static final int LEGACY_STORAGE_VERSION = 0;
    public static final int CURRENT_STORAGE_VERSION = 1;

    public static final String TRIGGER_INTERVAL = "interval";
    public static final String TRIGGER_CRON = "cron";
}
