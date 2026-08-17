package top.sywyar.pixivdownload.plugin.api.maintenance;

/**
 * 维护任务向宿主上报工作单元进度的纯 JDK 回调。
 */
@FunctionalInterface
public interface MaintenanceProgressReporter {

    /**
     * 忽略所有进度更新的报告器。
     */
    MaintenanceProgressReporter NOOP = (unitsDone, unitsTotal) -> { };

    /**
     * 更新。
     *
     * @param unitsDone {@code unitsDone} 对应的值
     * @param unitsTotal 工作单元数总数
     */
    void update(int unitsDone, int unitsTotal);

    /**
     * 返回对应值。
     *
     * @return 方法返回的 {@code MaintenanceProgressReporter} 实例
     */
    static MaintenanceProgressReporter noop() {
        return NOOP;
    }
}
