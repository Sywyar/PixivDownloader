package top.sywyar.pixivdownload.plugin.api.maintenance;

/**
 * 维护任务执行时的上下文。预留以便未来扩展（例如携带触发原因、运行 ID）。
 *
 * @param triggeredBy      触发来源，"schedule" / "manual" 等
 * @param startedAt        维护窗口的起始毫秒时间戳
 * @param progressReporter 当前任务的进度回调
 */
public record MaintenanceContext(String triggeredBy,
                                 long startedAt,
                                 MaintenanceProgressReporter progressReporter) {

    /**
     * 创建 {@code MaintenanceContext} 实例。
     *
     * @param triggeredBy 触发来源
     * @param startedAt 开始时间
     * @param progressReporter 进度报告器
     */
    public MaintenanceContext {
        progressReporter = progressReporter == null
                ? MaintenanceProgressReporter.noop()
                : progressReporter;
    }

    /**
     * 便利构造：不需要上报进度的任务使用 API 提供的空进度回调。
     *
     * @param triggeredBy 触发来源
     * @param startedAt 开始时间
     */
    public MaintenanceContext(String triggeredBy, long startedAt) {
        this(triggeredBy, startedAt, MaintenanceProgressReporter.noop());
    }

    /**
     * 更新进度。
     *
     * @param unitsDone {@code unitsDone} 对应的值
     * @param unitsTotal 工作单元数总数
     */
    public void updateProgress(int unitsDone, int unitsTotal) {
        progressReporter.update(unitsDone, unitsTotal);
    }
}
