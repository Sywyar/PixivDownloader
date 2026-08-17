package top.sywyar.pixivdownload.core.schedule.state;

/** 计划任务不能开始新一轮运行的机器可判定原因。 */
public enum ScheduleSuspendReason {
    /**
     * 表示 {@code MANUAL} 状态。
     */
    MANUAL,
    /**
     * 表示 {@code CREDENTIAL} 状态。
     */
    CREDENTIAL,
    /**
     * 表示 {@code POLICY} 状态。
     */
    POLICY,
    /**
     * 表示 {@code SOURCE_UNAVAILABLE} 状态。
     */
    SOURCE_UNAVAILABLE,
    /**
     * 表示 {@code EXECUTOR_UNAVAILABLE} 状态。
     */
    EXECUTOR_UNAVAILABLE,
    /**
     * 表示 {@code QUIESCED} 状态。
     */
    QUIESCED,
    /**
     * 表示 {@code MIGRATION_ERROR}。
     */
    MIGRATION_ERROR
}
