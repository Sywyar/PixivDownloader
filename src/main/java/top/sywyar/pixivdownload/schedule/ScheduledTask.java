package top.sywyar.pixivdownload.schedule;

/**
 * 计划任务行的内存视图。对应 {@code scheduled_tasks} 表的一行。
 *
 * <p><b>敏感字段约束：</b>本 record <b>故意不含</b> {@code cookie_snapshot}。该列是一份完整的
 * Pixiv 登录凭证，<b>绝不</b>进入任何行投影 / 列表 / 详情 / 导出 / stats / duplicates 视图——
 * 它只能经 {@link top.sywyar.pixivdownload.schedule.db.ScheduledTaskMapper#findCookieSnapshot}
 * 这一专用裸标量通道由调度器内部读取，结果绝不写日志 / 回显。
 *
 * <p>组件顺序必须与 {@code ScheduledTaskMapper.SELECT_TASK} 的列顺序一致
 * （MyBatis 按列序做构造器自动映射）。所有时间列均为 Unix epoch <b>毫秒</b>。
 *
 * @param triggerKind    {@code interval}（固定周期）或 {@code cron}（Cron 表达式）
 * @param intervalMinutes 固定周期分钟数（{@code triggerKind=interval} 时有效）
 * @param cronExpr        Spring Cron 表达式（{@code triggerKind=cron} 时有效）
 * @param cookieMode      {@code bound}（绑定管理员快照 Cookie）或 {@code restricted}（无 Cookie，仅匿名内容）
 * @param lastStatus      上轮运行结果标记（如 {@code OK} / {@code AUTH_EXPIRED} / {@code ERROR}）
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
        Long nextRunTime,
        Long lastRunTime,
        String lastStatus,
        long createdTime
) {
    public static final String TRIGGER_INTERVAL = "interval";
    public static final String TRIGGER_CRON = "cron";
    public static final String COOKIE_BOUND = "bound";
    public static final String COOKIE_RESTRICTED = "restricted";
}
