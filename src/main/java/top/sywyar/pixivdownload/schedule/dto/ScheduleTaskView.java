package top.sywyar.pixivdownload.schedule.dto;

import top.sywyar.pixivdownload.schedule.ScheduledTask;
import top.sywyar.pixivdownload.schedule.ScheduledTaskType;

/**
 * 计划任务对外视图（列表 / 详情）。
 *
 * <p><b>不含</b> {@code cookieSnapshot}：只用 {@code cookieBound} 布尔位告知前端是否已绑定凭证，
 * 凭证本身绝不回显。{@code proxy} 是任务级单独代理（{@code host:port}，非凭证、不含账号口令），
 * 可回显供前端「指定单独的 代理/cookie」弹窗预填编辑；{@code null} = 使用全局代理设置。
 *
 * <p>{@code runState} 是<b>瞬时运行态</b>（{@code QUEUED} / {@code RUNNING} / {@code null}），来自内存中的
 * {@link top.sywyar.pixivdownload.schedule.ScheduleRunState}，不落库；前端据它与持久化的 {@code lastStatus} /
 * {@code enabled} 共同决定状态灯。{@code lastMessage} 仅在 {@code lastStatus=ERROR} 时有值（失败原因摘要）。
 *
 * <p>{@code runStartedTime} 非 {@code null} 表示上次运行进入执行后未走到结果落库（进程被强杀中断），
 * 前端据此显示「上次运行被中断，已重新排期补齐」中断红灯；正常结束即清为 {@code null}。
 * 水位线 {@code watermarkId} 与 {@code cookieSnapshot} 是内部 / 凭证字段，<b>不</b>暴露给前端。
 *
 * <p>{@code accountId} 是非敏感 Pixiv userId（过度访问暂停按它分组）；{@code ackWarningTime} /
 * {@code pendingRetryArmed} 是非凭证运行态，可透出供前端展示账号级暂停与重试武装状态。
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
        String proxy,
        Long nextRunTime,
        Long lastRunTime,
        String lastStatus,
        String lastMessage,
        Long runStartedTime,
        String accountId,
        Long ackWarningTime,
        boolean pendingRetryArmed,
        String runState,
        long createdTime
) {
    public static ScheduleTaskView of(ScheduledTask t, String runState) {
        return new ScheduleTaskView(
                t.id(), t.name(), t.enabled(), t.type(), t.paramsJson(),
                t.triggerKind(), t.intervalMinutes(), t.cronExpr(), t.cookieMode(),
                ScheduledTask.COOKIE_BOUND.equals(t.cookieMode()),
                t.proxySnapshot(),
                t.nextRunTime(), t.lastRunTime(), t.lastStatus(), t.lastMessage(),
                t.runStartedTime(), t.accountId(), t.ackWarningTime(),
                t.pendingRetryArmed() == 1, runState, t.createdTime());
    }
}
