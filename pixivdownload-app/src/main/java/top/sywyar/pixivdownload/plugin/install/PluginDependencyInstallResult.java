package top.sywyar.pixivdownload.plugin.install;

/**
 * 市场安装目标插件时，由同一受信仓库自动补齐并成功落盘的依赖插件结果。它是轻量机器态投影，
 * 不递归嵌套完整 {@link PluginInstallResponse}。
 */
public record PluginDependencyInstallResult(
        String pluginId,
        String version,
        String previousVersion,
        String packageId,
        String targetVersion,
        String outcome,
        boolean accepted,
        boolean effectiveAfterRestart,
        boolean activated,
        boolean rolledBack,
        String rollbackVersion,
        String operation,
        String runtimePhase,
        boolean recoveryBlocked,
        boolean updated) {

    public PluginDependencyInstallResult(String pluginId, String version, String previousVersion,
                                         String packageId, String targetVersion, String outcome,
                                         boolean accepted, boolean effectiveAfterRestart,
                                         boolean activated, boolean rolledBack, String rollbackVersion,
                                         String operation, String runtimePhase, boolean updated) {
        this(pluginId, version, previousVersion, packageId, targetVersion, outcome,
                accepted, effectiveAfterRestart, activated, rolledBack, rollbackVersion,
                operation, runtimePhase, false, updated);
    }

    public static PluginDependencyInstallResult from(PluginInstallReport report) {
        return new PluginDependencyInstallResult(
                report.pluginId(),
                report.version(),
                report.previousVersion(),
                report.pluginId(),
                report.version(),
                report.outcome() != null ? report.outcome().name() : null,
                report.accepted(),
                report.effectiveAfterRestart(),
                report.activated(),
                report.rolledBack(),
                report.rollbackVersion(),
                report.operation() != null ? report.operation().name() : null,
                report.runtimePhase() != null ? report.runtimePhase().name() : null,
                report.recoveryBlocked(),
                report.updated());
    }
}
