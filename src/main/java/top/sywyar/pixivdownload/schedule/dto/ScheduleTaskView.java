package top.sywyar.pixivdownload.schedule.dto;

import top.sywyar.pixivdownload.schedule.ScheduledTask;
import top.sywyar.pixivdownload.schedule.ScheduledTaskType;

/**
 * 计划任务对外视图（列表 / 详情）。
 *
 * <p><b>不含</b> {@code cookieSnapshot}：只用 {@code cookieBound} 布尔位告知前端是否已绑定凭证，
 * 凭证本身绝不回显。
 *
 * <p>{@code runState} 是<b>瞬时运行态</b>（{@code QUEUED} / {@code RUNNING} / {@code null}），来自内存中的
 * {@link top.sywyar.pixivdownload.schedule.ScheduleRunState}，不落库；前端据它与持久化的 {@code lastStatus} /
 * {@code enabled} 共同决定状态灯。{@code lastMessage} 仅在 {@code lastStatus=ERROR} 时有值（失败原因摘要）。
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
        String lastMessage,
        String runState,
        long createdTime
) {
    public static ScheduleTaskView of(ScheduledTask t, String runState) {
        return new ScheduleTaskView(
                t.id(), t.name(), t.enabled(), t.type(), t.paramsJson(),
                t.triggerKind(), t.intervalMinutes(), t.cronExpr(), t.cookieMode(),
                ScheduledTask.COOKIE_BOUND.equals(t.cookieMode()),
                t.nextRunTime(), t.lastRunTime(), t.lastStatus(), t.lastMessage(),
                runState, t.createdTime());
    }
}
