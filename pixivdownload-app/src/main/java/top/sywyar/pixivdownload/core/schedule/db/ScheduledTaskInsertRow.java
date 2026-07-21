package top.sywyar.pixivdownload.core.schedule.db;

import lombok.Data;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;

/** {@code scheduled_tasks} 的 MyBatis generated-key 可变插入行；不得越过 app 实现边界。 */
@Data
final class ScheduledTaskInsertRow {
    private Long id;
    private String name;
    private boolean enabled = true;
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
}
