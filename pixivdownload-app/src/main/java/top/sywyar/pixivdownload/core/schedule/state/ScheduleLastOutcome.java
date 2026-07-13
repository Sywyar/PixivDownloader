package top.sywyar.pixivdownload.core.schedule.state;

/** 计划任务最近一次已结束运行的结果；与启用意图、在途状态和挂起原因正交。 */
public enum ScheduleLastOutcome {
    NEVER,
    OK,
    ERROR,
    CANCELLED,
    INTERRUPTED
}
