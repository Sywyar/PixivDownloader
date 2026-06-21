package top.sywyar.pixivdownload.core.schedule;

/**
 * {@code scheduled_task_pending} 表的一行：因可恢复失败被跳过、待重试的单作品。
 *
 * <p>组件顺序与底层 {@code scheduled_task_pending} 行投影的列别名一致（数据访问实现按列序构造器映射）。
 * 全为非凭证字段，可按需透出给前端。所有时间列均为 Unix epoch <b>毫秒</b>。
 *
 * @param attempts        已重试次数；到 {@code schedule.pending-max-attempts} 上限即标「需人工」
 * @param firstSeenTime   首次被隔离的时刻；重试入队冲突时保留不变
 * @param lastAttemptTime 最近一次尝试时刻
 */
public record ScheduledTaskPending(
        long taskId,
        long workId,
        String reason,
        int attempts,
        Long firstSeenTime,
        Long lastAttemptTime
) {
}
