package top.sywyar.pixivdownload.plugin.api.schedule.work;

/**
 * 单个作品类型在一轮执行中的最终记账统计。每次实际调用作品执行器的尝试都必须归入成功结果之一，
 * 或在失败信息耐久写入 pending 后归入 pending。
 */
public record ScheduledWorkRunStatistics(
        long attemptedWorkCount,
        long completedWorkCount,
        long alreadyCompletedWorkCount,
        long skippedWorkCount,
        long pendingWorkCount
) {

    public ScheduledWorkRunStatistics {
        if (attemptedWorkCount < 0
                || completedWorkCount < 0
                || alreadyCompletedWorkCount < 0
                || skippedWorkCount < 0
                || pendingWorkCount < 0) {
            throw new IllegalArgumentException("work run statistics must not be negative");
        }
        long accountedWorkCount;
        try {
            accountedWorkCount = Math.addExact(
                    Math.addExact(completedWorkCount, alreadyCompletedWorkCount),
                    Math.addExact(skippedWorkCount, pendingWorkCount));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("work run statistics exceed long range");
        }
        if (attemptedWorkCount != accountedWorkCount) {
            throw new IllegalArgumentException("attempted work count must equal accounted work count");
        }
    }
}
