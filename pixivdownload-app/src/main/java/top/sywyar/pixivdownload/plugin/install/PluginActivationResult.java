package top.sywyar.pixivdownload.plugin.install;

import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginOperation;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;

import java.util.List;

/** 文件事务与运行时激活的统一结果。 */
public record PluginActivationResult(
        String transactionId,
        PluginInstallResult installResult,
        boolean activated,
        boolean rolledBack,
        String rollbackVersion,
        ExternalPluginOperation operation,
        PluginRuntimePhase runtimePhase,
        List<PluginDependencyProblem> dependencyProblems) {

    public PluginActivationResult {
        dependencyProblems = dependencyProblems != null ? List.copyOf(dependencyProblems) : List.of();
    }

    public PluginActivationResult(String transactionId, PluginInstallResult installResult,
                                  boolean activated, boolean rolledBack, String rollbackVersion,
                                  ExternalPluginOperation operation, PluginRuntimePhase runtimePhase) {
        this(transactionId, installResult, activated, rolledBack, rollbackVersion, operation, runtimePhase, List.of());
    }
}
