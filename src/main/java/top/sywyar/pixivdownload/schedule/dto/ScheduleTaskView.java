package top.sywyar.pixivdownload.schedule.dto;

import top.sywyar.pixivdownload.schedule.ScheduledTask;
import top.sywyar.pixivdownload.schedule.ScheduledTaskType;

/**
 * 计划任务对外视图（列表 / 详情）。
 *
 * <p><b>不含</b> {@code cookieSnapshot}：只用 {@code cookieBound} 布尔位告知前端是否已绑定凭证，
 * 凭证本身绝不回显。
 */
public record ScheduleTaskView(
        Long id,
        String name,
        boolean enabled,
        ScheduledTaskType type,
        String paramsJson,
        String triggerKind,
        Integer intervalMinutes,
        String cronExpr,
        String cookieMode,
        boolean cookieBound,
        Long nextRunTime,
        Long lastRunTime,
        String lastStatus,
        long createdTime
) {
    public static ScheduleTaskView of(ScheduledTask t) {
        return new ScheduleTaskView(
                t.id(), t.name(), t.enabled(), t.type(), t.paramsJson(),
                t.triggerKind(), t.intervalMinutes(), t.cronExpr(), t.cookieMode(),
                ScheduledTask.COOKIE_BOUND.equals(t.cookieMode()),
                t.nextRunTime(), t.lastRunTime(), t.lastStatus(), t.createdTime());
    }
}
