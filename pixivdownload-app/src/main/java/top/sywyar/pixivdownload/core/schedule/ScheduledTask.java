package top.sywyar.pixivdownload.core.schedule;

import top.sywyar.pixivdownload.plugin.registry.ScheduledSourceRegistry;

/**
 * 计划任务行的内存视图。对应 {@code scheduled_tasks} 表的一行。
 *
 * <p><b>敏感字段约束：</b>本 record <b>故意不含</b> {@code cookie_snapshot}。该列是一份完整的
 * Pixiv 登录凭证，<b>绝不</b>进入任何行投影 / 列表 / 详情 / 导出 / stats / duplicates 视图——
 * 它只能经 {@link top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore#findCookieSnapshot}
 * 这一专用裸标量通道由调度器内部读取，结果绝不写日志 / 回显。
 *
 * <p>组件顺序必须与底层 {@code scheduled_tasks} 行投影的列顺序一致（数据访问实现按列序做构造器自动映射）。
 * 所有时间列均为 Unix epoch <b>毫秒</b>。
 *
 * @param triggerKind    {@code interval}（固定周期）或 {@code cron}（Cron 表达式）
 * @param intervalMinutes 固定周期分钟数（{@code triggerKind=interval} 时有效）
 * @param cronExpr        Spring Cron 表达式（{@code triggerKind=cron} 时有效）
 * @param cookieMode      {@code bound}（绑定管理员快照 Cookie）或 {@code restricted}（无 Cookie，仅匿名内容）
 * @param proxySnapshot   任务级单独代理（{@code host:port}，非凭证）；{@code null} = 使用全局代理设置。
 *                        本轮运行中该任务对 Pixiv 的全部出站请求（发现 / 元数据 / 下载 / 站内信检测）都改走它
 * @param lastStatus      上轮运行结果标记（如 {@code OK} / {@code AUTH_EXPIRED} / {@code ERROR}）
 * @param lastMessage     上轮失败原因摘要（仅 {@code ERROR} 时有值，已截断、绝不含 Cookie）；其余状态为 {@code null}
 * @param watermarkId     水位线：上一轮完整跑完时发现到的最新作品 ID（仅 USER_NEW / 增量 SEARCH 用，全量跑完才更新）；首次为 {@code null}
 * @param runStartedTime  本轮进入执行的时刻（毫秒）；正常结束即清为 {@code null}，残留则表示上次运行被进程强杀中断
 * @param accountId        授权时从 PHPSESSID 下划线前缀解析出的非敏感 Pixiv userId；未授权 / 解析失败为 {@code null}。过度访问冻结按它判定「同账号」
 * @param ackWarningTime   管理员「无视风险」显式放行过的最新警告 modifiedAt（毫秒）；不超过它的过度访问警告不再触发暂停
 * @param pendingRetryArmed 管理员处理完异常后置 1：下一轮运行开始先把隔离表入队重试，重试后清 0
 */
public record ScheduledTask(
        Long id,
        String name,
        boolean enabled,
        ScheduledTaskType type,
        String paramsJson,
        String triggerKind,
        Integer intervalMinutes,
        String cronExpr,
        String cookieMode,
        String proxySnapshot,
        Long nextRunTime,
        Long lastRunTime,
        String lastStatus,
        String lastMessage,
        Long watermarkId,
        Long runStartedTime,
        String accountId,
        Long ackWarningTime,
        int pendingRetryArmed,
        long createdTime
) {
    public static final String TRIGGER_INTERVAL = "interval";
    public static final String TRIGGER_CRON = "cron";
    public static final String COOKIE_BOUND = "bound";
    public static final String COOKIE_RESTRICTED = "restricted";

    /** 上轮过度访问警告 → 账号级暂停（恢复入口为账号级两个按钮）。 */
    public static final String STATUS_OVERUSE_PAUSED = "OVERUSE_PAUSED";
    /** Cookie 失效 → 任务级挂起（恢复入口为重新授权 cookie）。 */
    public static final String STATUS_AUTH_EXPIRED = "AUTH_EXPIRED";
    /** 管理员手动暂停（任务级，不冻账号、不发邮件；恢复入口为「恢复」按钮）。 */
    public static final String STATUS_PAUSED = "PAUSED";
    /**
     * 来源不可用 → 任务级挂起（不发现 / 不派发）。任务的 {@code type} 在来源注册中心
     * （{@code ScheduledSourceRegistry}）解析不到对应来源 provider（来源插件被禁 / 卸载，
     * 或该类型已被移除）时由调度器写入，经 {@code findDue} 状态门挡住、不自动重跑。
     * 来源恢复后的显式重激活入口另行实现。
     */
    public static final String STATUS_SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE";
}
