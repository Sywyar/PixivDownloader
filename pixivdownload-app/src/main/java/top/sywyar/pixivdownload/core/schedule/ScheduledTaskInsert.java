package top.sywyar.pixivdownload.core.schedule;

import lombok.Data;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;

/**
 * {@code scheduled_tasks} 插入用的可变载体。
 *
 * <p>用可变 bean 是为了让 MyBatis {@code useGeneratedKeys} 回填自增 id。新建任务显式写入
 * {@link ScheduledTask#CURRENT_STORAGE_VERSION}，不能依赖数据库给旧行准备的默认值 0。
 */
@Data
public class ScheduledTaskInsert {
    private Long id;
    private String name;
    private boolean enabled;
    private String sourceType;
    private String sourceOwnerPluginId;
    private String definitionSchema;
    private Integer definitionVersion;
    private String definitionJson;
    private String presentationJson;
    private String triggerKind;
    private Integer intervalMinutes;
    private String cronExpr;
    private String proxySnapshot;
    private Long nextRunTime;
    private Long lastRunTime;
    private String checkpointSchema;
    private Integer checkpointVersion;
    private String checkpointJson;
    private int storageVersion = ScheduledTask.CURRENT_STORAGE_VERSION;
    private ScheduleRunState runState;
    private String runClaimToken;
    private ScheduleLastOutcome lastOutcome = ScheduleLastOutcome.NEVER;
    private String outcomeCode;
    private String outcomeMessage;
    private ScheduleSuspendReason suspendReason;
    private String suspendCode;
    private String suspendDetailJson;
    private long stateVersion;
    private long createdTime;

    /** 可选的初始凭证元数据；secret 由 Store 在任务插入后写入专用凭证表。 */
    private String credentialPolicyOwnerPluginId;
    private String credentialPolicyId;
    private String credentialAccountKey;
    private String credentialPolicyStateJson = "{}";
    private String credentialSecretReference;
    private Long credentialUpdatedTime;
    /** 敏感裸标量，不参与 {@link #toString()}。 */
    private transient String credentialSecret;

    @Override
    public String toString() {
        return "ScheduledTaskInsert{id=" + id
                + ", name='" + name + '\''
                + ", sourceType='" + sourceType + '\''
                + ", storageVersion=" + storageVersion
                + ", credentialSecret=<redacted>}";
    }
}
