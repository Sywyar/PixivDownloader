package top.sywyar.pixivdownload.plugin.api.maintenance;

/**
 * 维护任务向宿主上报工作单元进度的纯 JDK 回调。
 */
@FunctionalInterface
public interface MaintenanceProgressReporter {

    MaintenanceProgressReporter NOOP = (unitsDone, unitsTotal) -> { };

    void update(int unitsDone, int unitsTotal);

    static MaintenanceProgressReporter noop() {
        return NOOP;
    }
}
