package top.sywyar.pixivdownload.schedule.dto;

import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;

/**
 * 隔离表（待重试）行的对外视图：供前端「本任务待重试 / 需人工」面板展示。
 *
 * <p>{@code needsManual} 由调用方按 {@code attempts >= schedule.pending-max-attempts} 计算——到上限即停止自动重试、等管理员手动清。
 * 全为非凭证字段。
 */
public record SchedulePendingView(
        long taskId,
        String workType,
        String workId,
        String presentationJson,
        String reasonCode,
        String reasonDetailJson,
        int attempts,
        boolean needsManual,
        Long firstSeenTime,
        Long lastAttemptTime
) {
    public static SchedulePendingView of(ScheduledPendingWork p, int maxAttempts) {
        return new SchedulePendingView(
                p.taskId(), p.workType(), p.workId(), p.presentationJson(),
                p.reasonCode(), p.reasonDetailJson(), p.attempts(),
                p.attempts() >= maxAttempts, p.firstSeenTime(), p.lastAttemptTime());
    }
}
