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
    /** 任务级单独代理（host:port，非凭证）；创建时为 null = 使用全局代理设置。 */
    private String proxySnapshot;
    private Long nextRunTime;
    private Long lastRunTime;
    private String lastStatus;
    private String lastMessage;
    /** 水位线：上一轮完整跑完时发现到的最新作品 ID（创建时为 null）。 */
    private Long watermarkId;
    /** 本轮进入执行的时刻（毫秒）；创建时为 null。 */
    private Long runStartedTime;
    /** 非敏感 Pixiv userId（PHPSESSID 下划线前缀）；创建时为 null，授权后写入。 */
    private String accountId;
    /** 管理员显式放行的最新警告 modifiedAt（毫秒）；创建时为 null。 */
    private Long ackWarningTime;
    /** 重试武装位；创建时为 0。 */
    private int pendingRetryArmed;
    private long createdTime;
}
