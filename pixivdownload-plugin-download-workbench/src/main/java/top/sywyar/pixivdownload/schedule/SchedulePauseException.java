package top.sywyar.pixivdownload.schedule;

/**
 * 用户在任务运行中点了「暂停」时上抛，让 {@code ScheduleExecutor.runTaskAndRecord} 干净 unwind 本轮：
 * 管理员的 {@code MANUAL} 挂起已先持久化，本异常只终止持有原 claim 的本轮执行；不冻账号、不发邮件、
 * 不提交候选 checkpoint。
 *
 * <p>触发点：{@code ScheduleService.pause} 会原子写入挂起原因并通过
 * {@code ScheduleRunState.requestCancel} 在内存里给当前 Claim 打取消标记；
 * executor 在每个作品派发前（{@code WorkRunner.process} 入口）轮询该标记，命中即抛。
 *
 * <p>已派发的下载不回滚；本轮发现到的可恢复失败已进隔离表，下次手动恢复或重新启用后照常重试。
 */
public class SchedulePauseException extends Exception {
    public SchedulePauseException() {
        super("schedule task paused by user");
    }
}
