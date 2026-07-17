package top.sywyar.pixivdownload.plugin.api.maintenance;

/**
 * 维护任务执行时的上下文。预留以便未来扩展（例如携带触发原因、运行 ID）。
 *
 * @param triggeredBy      触发来源，"schedule" / "manual" 等
 * @param startedAt        维护窗口的起始毫秒时间戳
 * @param progressReporter 当前任务的宿主进度回调
 */
public record MaintenanceContext(String triggeredBy,
                                 long startedAt,
                                 MaintenanceProgressReporter progressReporter) {

    public MaintenanceContext {
        progressReporter = progressReporter == null
                ? MaintenanceProgressReporter.noop()
                : progressReporter;
    }

    public MaintenanceContext(String triggeredBy, long startedAt) {
        this(triggeredBy, startedAt, MaintenanceProgressReporter.noop());
    }

    public void updateProgress(int unitsDone, int unitsTotal) {
        progressReporter.update(unitsDone, unitsTotal);
    }
}
