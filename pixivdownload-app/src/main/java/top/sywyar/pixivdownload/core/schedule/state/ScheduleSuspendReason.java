package top.sywyar.pixivdownload.core.schedule.state;

/** 计划任务不能开始新一轮运行的机器可判定原因。 */
public enum ScheduleSuspendReason {
    MANUAL,
    CREDENTIAL,
    POLICY,
    SOURCE_UNAVAILABLE,
    EXECUTOR_UNAVAILABLE,
    QUIESCED,
    MIGRATION_ERROR
}
