package top.sywyar.pixivdownload.plugin.api.schedule.guard;

/** 宿主强制调用 Guard 的固定检查点。 */
public enum ScheduledGuardPoint {
    /**
     * 表示 {@code RUN_START} 状态。
     */
    RUN_START,
    /**
     * 表示 {@code WORK_BATCH} 状态。
     */
    WORK_BATCH,
    /**
     * 表示 {@code RUN_END} 状态。
     */
    RUN_END,
    /**
     * 表示 {@code RUN_FAILURE}。
     */
    RUN_FAILURE
}
