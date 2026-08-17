package top.sywyar.pixivdownload.core.schedule.state;

/** 计划任务最近一次已结束运行的结果；与启用意图、在途状态和挂起原因正交。 */
public enum ScheduleLastOutcome {
    /**
     * 表示 {@code NEVER} 状态。
     */
    NEVER,
    /**
     * 表示 {@code OK} 状态。
     */
    OK,
    /**
     * 表示 {@code ERROR} 状态。
     */
    ERROR,
    /**
     * 表示 {@code CANCELLED} 状态。
     */
    CANCELLED,
    /**
     * 表示 {@code INTERRUPTED}。
     */
    INTERRUPTED
}
