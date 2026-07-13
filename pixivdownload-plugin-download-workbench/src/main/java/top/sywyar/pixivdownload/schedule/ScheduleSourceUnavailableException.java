package top.sywyar.pixivdownload.schedule;

/**
 * 来源能力不可用信号：任务的 {@code type} 无法从
 * {@link top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry} 取得完整 generation lease
 * （来源或作品 owner 被禁用、卸载、撤回，或类型已移除）时，由 {@code ScheduleExecutor.runTask} 上抛，
 * 让 {@code runTaskAndRecord} 标 {@link top.sywyar.pixivdownload.core.schedule.ScheduledTask#STATUS_SOURCE_UNAVAILABLE}、清 {@code run_started_time}，
 * <b>绝不发现 / 派发</b>，并经 {@code findDue} 状态门挡住自动重跑。
 *
 * <p>owner quiesce 会撤回 publication 并取消正在运行的复合 lease；调度壳在下一个协作式取消点以同一信号干净挂起。
 */
public class ScheduleSourceUnavailableException extends Exception {

    /** 解析失败的任务 type（数据库存量值 / 枚举名）。仅类型标识，绝不含凭证，可安全入 {@code last_message} / 日志。 */
    private final String unresolvedType;

    public ScheduleSourceUnavailableException(String unresolvedType) {
        super("scheduled source unavailable: " + unresolvedType);
        this.unresolvedType = unresolvedType;
    }

    public String unresolvedType() {
        return unresolvedType;
    }
}
