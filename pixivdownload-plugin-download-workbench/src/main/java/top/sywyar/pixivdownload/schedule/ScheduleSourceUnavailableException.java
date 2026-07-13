package top.sywyar.pixivdownload.schedule;

/**
 * 来源能力不可用信号：任务的来源无法从
 * {@link top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry} 取得 generation lease，
 * 或持久化 owner 与当前 publication 不匹配。作品执行器缺席使用独立的
 * {@link ScheduleExecutorUnavailableException}，不得混淆两种恢复原因。
 *
 * <p>owner quiesce 会撤回 publication 并取消正在运行的复合 lease；调度壳在下一个协作式取消点以同一信号干净挂起。
 */
public class ScheduleSourceUnavailableException extends Exception {

    /** 解析失败的 canonical source type。仅类型标识，绝不含凭证。 */
    private final String unresolvedType;

    public ScheduleSourceUnavailableException(String unresolvedType) {
        super("scheduled source unavailable: " + unresolvedType);
        this.unresolvedType = unresolvedType;
    }

    public String unresolvedType() {
        return unresolvedType;
    }
}
