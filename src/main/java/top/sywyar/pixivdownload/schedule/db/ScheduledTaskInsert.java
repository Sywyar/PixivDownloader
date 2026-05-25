package top.sywyar.pixivdownload.schedule.db;

import lombok.Data;
import top.sywyar.pixivdownload.schedule.ScheduledTaskType;

/**
 * {@code scheduled_tasks} 插入用的可变载体。
 *
 * <p>用可变 bean（而非 {@link top.sywyar.pixivdownload.schedule.ScheduledTask} record）是为了让
 * MyBatis 的 {@code useGeneratedKeys} 能把自增主键回填到 {@link #id}。
 */
@Data
public class ScheduledTaskInsert {
    private Long id;
    private String name;
    private boolean enabled;
    private ScheduledTaskType type;
    private String paramsJson;
    private String triggerKind;
    private Integer intervalMinutes;
    private String cronExpr;
    private String cookieMode;
    /** 完整登录凭证，敏感：绝不写日志 / 回显。 */
    private String cookieSnapshot;
    private Long nextRunTime;
    private Long lastRunTime;
    private String lastStatus;
    private long createdTime;
}
