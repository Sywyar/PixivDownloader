package top.sywyar.pixivdownload.plugin.api.schedule.guard;

/** 宿主强制调用 Guard 的固定检查点。 */
public enum ScheduledGuardPoint {
    RUN_START,
    WORK_BATCH,
    RUN_END,
    RUN_FAILURE
}
