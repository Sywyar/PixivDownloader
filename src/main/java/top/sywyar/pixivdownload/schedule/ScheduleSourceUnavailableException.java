package top.sywyar.pixivdownload.schedule;

/**
 * 来源不可用信号：任务的 {@code type} 在
 * {@link top.sywyar.pixivdownload.plugin.ScheduledSourceRegistry} 解析不到对应来源 provider
 * （来源插件被禁 / 卸载，或该类型已被移除）时，由 {@code ScheduleExecutor.runTask} 顶部的来源解析门上抛，
 * 让 {@code runTaskAndRecord} 标 {@link ScheduledTask#STATUS_SOURCE_UNAVAILABLE}、清 {@code run_started_time}，
 * <b>绝不发现 / 派发</b>，并经 {@code findDue} 状态门挡住自动重跑。
 *
 * <p>当前 7 个内置来源恒由核心插件贡献，故该信号在生产路径不可达——它是为插件禁用 / 热卸载
 * 语义预留的解析门终态，目前仅以单元测试钉死其正确性。
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
