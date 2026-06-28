package top.sywyar.pixivdownload.plugin;

import top.sywyar.pixivdownload.plugin.runtime.install.PluginInstallResult;

/** 文件事务与运行时激活的统一结果。 */
public record PluginActivationResult(
        String transactionId,
        PluginInstallResult installResult,
        boolean activated,
        boolean rolledBack,
        String rollbackVersion,
        ExternalPluginOperation operation,
        PluginRuntimePhase runtimePhase) {
}
