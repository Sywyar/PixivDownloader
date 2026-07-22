package top.sywyar.pixivdownload.plugin.runtime.install.transaction;

import java.util.Objects;

/** 不触盘的不可变恢复准入快照，供状态与管理面在 BLOCKED 时短路磁盘扫描。 */
public record PluginRecoveryGateSnapshot(
        PluginRecoveryGateState state,
        PluginTransactionRecoveryReport report) {

    public PluginRecoveryGateSnapshot {
        state = Objects.requireNonNull(state, "state");
        report = Objects.requireNonNull(report, "report");
        if ((state == PluginRecoveryGateState.UNCHECKED || state == PluginRecoveryGateState.SAFE)
                && !report.safeToScan()) {
            throw new IllegalArgumentException(state + " recovery gate requires an empty success report");
        }
        if (state == PluginRecoveryGateState.BLOCKED && report.safeToScan()) {
            throw new IllegalArgumentException("BLOCKED recovery gate requires at least one failure");
        }
    }

    public boolean safeToScan() {
        return state == PluginRecoveryGateState.SAFE && report.safeToScan();
    }

    public static PluginRecoveryGateSnapshot unchecked() {
        return new PluginRecoveryGateSnapshot(
                PluginRecoveryGateState.UNCHECKED, PluginTransactionRecoveryReport.success());
    }

    public static PluginRecoveryGateSnapshot safe(PluginTransactionRecoveryReport report) {
        return new PluginRecoveryGateSnapshot(PluginRecoveryGateState.SAFE, report);
    }

    public static PluginRecoveryGateSnapshot blocked(PluginTransactionRecoveryReport report) {
        return new PluginRecoveryGateSnapshot(PluginRecoveryGateState.BLOCKED, report);
    }
}
