package top.sywyar.pixivdownload.maintenance;

import top.sywyar.pixivdownload.guiswing.SwingHost;

public final class MaintenanceStatusHolder {
    private MaintenanceStatusHolder() {}
    public static Snapshot snapshot() {
        var value = SwingHost.host().maintenanceSnapshot();
        return new Snapshot(value.active(), value.trigger(), value.index(), value.total(), value.taskName(),
                value.taskStartedAt(), value.unitsDone(), value.unitsTotal());
    }
    public record Snapshot(boolean active, String trigger, int index, int total, String taskName,
                           long taskStartedAt, int unitsDone, int unitsTotal) {}
}
