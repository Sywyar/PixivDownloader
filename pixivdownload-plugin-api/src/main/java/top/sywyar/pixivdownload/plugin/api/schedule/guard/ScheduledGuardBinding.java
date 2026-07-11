package top.sywyar.pixivdownload.plugin.api.schedule.guard;

import java.util.Set;

/** 一个执行计划对 Guard 的调用声明。 */
public record ScheduledGuardBinding(
        String guardId,
        Set<ScheduledGuardPoint> points,
        int workBatchSize
) {

    public ScheduledGuardBinding {
        if (guardId == null || guardId.isBlank()) {
            throw new IllegalArgumentException("guard id must not be blank");
        }
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("guard points must not be empty");
        }
        guardId = guardId.trim();
        points = Set.copyOf(points);
        if (points.contains(ScheduledGuardPoint.WORK_BATCH)) {
            if (workBatchSize <= 0) {
                throw new IllegalArgumentException("work-batch guard must declare a positive batch size");
            }
        } else if (workBatchSize != 0) {
            throw new IllegalArgumentException("guard without work-batch point must use batch size 0");
        }
    }
}
