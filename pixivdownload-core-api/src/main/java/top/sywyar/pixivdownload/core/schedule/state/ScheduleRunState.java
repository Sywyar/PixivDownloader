package top.sywyar.pixivdownload.core.schedule.state;

/**
 * 计划任务的持久化在途状态。
 *
 * <p>空值表示当前没有认领；{@link #CANCEL_REQUESTED} 保留原认领 token，直到执行方用同一 token
 * 完成取消收尾，避免管理员挂起被旧运行结果覆盖。
 */
public enum ScheduleRunState {
    QUEUED,
    RUNNING,
    CANCEL_REQUESTED
}
