package top.sywyar.pixivdownload.plugin.install;

import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginOperation;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.management.PluginManagementController;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService.PluginDependencyView;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;

import java.util.List;

/**
 * 一次本地插件包安装尝试的结构化结果（后端事实，<b>不</b>含 i18n / HTTP）：由 {@link PluginInstallService} 产出，
 * 供 {@link PluginManagementController} 叠加本地化 message 与 HTTP 状态后形成 {@link PluginInstallResponse}。
 *
 * <p>Spring 运行时使用统一编排器完成校验、事务落盘和即时激活；保留的兼容构造器仍可表达只落盘、重启后生效的结果。
 *
 * @param outcome                 结果分类（稳定机器码经 {@link PluginInstallOutcome#name()} 投影到响应）
 * @param accepted                是否最终落盘存在（新装 / 升级 / 降级 / 已存在）
 * @param effectiveAfterRestart   是否存在无法即时激活、需重启确认的兼容结局
 * @param pluginId                安装包的插件 id（描述符不可读的非法包为 {@code null}）
 * @param version                 安装包的版本（描述符不可读时为 {@code null}）
 * @param previousVersion         被取代 / 已存在的旧版本（升级 / 降级 / 重复时在场，否则 {@code null}）
 * @param dependencies            描述符声明的插件间依赖投影（依赖诊断；描述符不可读时为空列表）
 * @param unsatisfiedDependencies 当前不可达的<b>非可选</b>依赖 id（机器可读摘要；缺失或版本不满足时可阻断安装 / 激活）
 * @param dependencyProblems      未满足依赖的结构化诊断
 * @param diagnostics             安装器英文诊断说明（排错用，非用户文案）
 * @param recoveryBlocked         本次操作是否留下必须先恢复的磁盘事务，后续生命周期变更会被恢复门阻断
 * @param dependencyInstallResults 本次市场安装过程中自动安装成功的依赖插件结果（本地上传等入口为空）
 */
public record PluginInstallReport(
        PluginInstallOutcome outcome,
        boolean accepted,
        boolean effectiveAfterRestart,
        String pluginId,
        String version,
        String previousVersion,
        List<PluginDependencyView> dependencies,
        List<String> unsatisfiedDependencies,
        List<PluginDependencyProblem> dependencyProblems,
        List<String> diagnostics,
        String transactionId,
        boolean activated,
        boolean rolledBack,
        String rollbackVersion,
        ExternalPluginOperation operation,
        PluginRuntimePhase runtimePhase,
        boolean recoveryBlocked,
        boolean updated,
        List<PluginDependencyInstallResult> dependencyInstallResults) {

    public PluginInstallReport {
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
        unsatisfiedDependencies = unsatisfiedDependencies != null ? List.copyOf(unsatisfiedDependencies) : List.of();
        dependencyProblems = dependencyProblems != null ? List.copyOf(dependencyProblems) : List.of();
        diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
        dependencyInstallResults = dependencyInstallResults != null
                ? List.copyOf(dependencyInstallResults) : List.of();
    }

    public PluginInstallReport(PluginInstallOutcome outcome, boolean accepted, boolean effectiveAfterRestart,
                               String pluginId, String version, String previousVersion,
                               List<PluginDependencyView> dependencies, List<String> unsatisfiedDependencies,
                               List<String> diagnostics) {
        this(outcome, accepted, effectiveAfterRestart, pluginId, version, previousVersion, dependencies,
                unsatisfiedDependencies, List.of(), diagnostics, null, false, false, null,
                ExternalPluginOperation.IDLE, null, false, false, List.of());
    }

    public PluginInstallReport(PluginInstallOutcome outcome, boolean accepted, boolean effectiveAfterRestart,
                               String pluginId, String version, String previousVersion,
                               List<PluginDependencyView> dependencies, List<String> unsatisfiedDependencies,
                               List<PluginDependencyProblem> dependencyProblems, List<String> diagnostics) {
        this(outcome, accepted, effectiveAfterRestart, pluginId, version, previousVersion, dependencies,
                unsatisfiedDependencies, dependencyProblems, diagnostics, null, false, false, null,
                ExternalPluginOperation.IDLE, null, false, false, List.of());
    }

    public PluginInstallReport(PluginInstallOutcome outcome, boolean accepted, boolean effectiveAfterRestart,
                               String pluginId, String version, String previousVersion,
                               List<PluginDependencyView> dependencies, List<String> unsatisfiedDependencies,
                               List<String> diagnostics, String transactionId,
                               boolean activated, boolean rolledBack, String rollbackVersion,
                               ExternalPluginOperation operation, PluginRuntimePhase runtimePhase, boolean updated) {
        this(outcome, accepted, effectiveAfterRestart, pluginId, version, previousVersion, dependencies,
                unsatisfiedDependencies, List.of(), diagnostics, transactionId, activated, rolledBack,
                rollbackVersion, operation, runtimePhase, false, updated, List.of());
    }

    public PluginInstallReport(PluginInstallOutcome outcome, boolean accepted, boolean effectiveAfterRestart,
                               String pluginId, String version, String previousVersion,
                               List<PluginDependencyView> dependencies, List<String> unsatisfiedDependencies,
                               List<PluginDependencyProblem> dependencyProblems, List<String> diagnostics,
                               String transactionId, boolean activated, boolean rolledBack, String rollbackVersion,
                               ExternalPluginOperation operation, PluginRuntimePhase runtimePhase, boolean updated) {
        this(outcome, accepted, effectiveAfterRestart, pluginId, version, previousVersion, dependencies,
                unsatisfiedDependencies, dependencyProblems, diagnostics, transactionId, activated, rolledBack,
                rollbackVersion, operation, runtimePhase, false, updated, List.of());
    }

    public PluginInstallReport(PluginInstallOutcome outcome, boolean accepted, boolean effectiveAfterRestart,
                               String pluginId, String version, String previousVersion,
                               List<PluginDependencyView> dependencies, List<String> unsatisfiedDependencies,
                               List<PluginDependencyProblem> dependencyProblems, List<String> diagnostics,
                               String transactionId, boolean activated, boolean rolledBack, String rollbackVersion,
                               ExternalPluginOperation operation, PluginRuntimePhase runtimePhase,
                               boolean recoveryBlocked, boolean updated) {
        this(outcome, accepted, effectiveAfterRestart, pluginId, version, previousVersion, dependencies,
                unsatisfiedDependencies, dependencyProblems, diagnostics, transactionId, activated, rolledBack,
                rollbackVersion, operation, runtimePhase, recoveryBlocked, updated, List.of());
    }

    public PluginInstallReport withDependencyInstallResults(
            List<PluginDependencyInstallResult> dependencyInstallResults) {
        boolean blocked = recoveryBlocked || dependencyInstallResults != null
                && dependencyInstallResults.stream().anyMatch(PluginDependencyInstallResult::recoveryBlocked);
        return new PluginInstallReport(outcome, accepted, effectiveAfterRestart,
                pluginId, version, previousVersion, dependencies, unsatisfiedDependencies,
                dependencyProblems, diagnostics, transactionId, activated, rolledBack, rollbackVersion,
                operation, runtimePhase, blocked, updated, dependencyInstallResults);
    }

    public PluginInstallReport withRecoveryBlocked() {
        if (recoveryBlocked) {
            return this;
        }
        return new PluginInstallReport(outcome, accepted, effectiveAfterRestart,
                pluginId, version, previousVersion, dependencies, unsatisfiedDependencies,
                dependencyProblems, diagnostics, transactionId, activated, rolledBack, rollbackVersion,
                operation, runtimePhase, true, updated, dependencyInstallResults);
    }
}
