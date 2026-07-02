package top.sywyar.pixivdownload.plugin.install;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;

/**
 * 插件依赖未满足的结构化诊断。用于安装拒绝、市场自动依赖安装失败、运行期启用阻断等场景。
 *
 * @param pluginId         被依赖插件 id
 * @param versionSupport   依赖声明中的版本要求
 * @param optional         是否为可选依赖
 * @param installedVersion 当前可见版本（缺失时为 {@code null}）
 * @param status           当前可见状态或下游安装结果（可空）
 * @param reason           稳定原因码
 * @param detail           英文诊断说明（排错用，非用户文案）
 */
public record PluginDependencyProblem(
        String pluginId,
        String versionSupport,
        boolean optional,
        String installedVersion,
        String status,
        Reason reason,
        String detail) {

    public static PluginDependencyProblem missing(PluginDependencyRef dependency) {
        return new PluginDependencyProblem(dependency.pluginId(), dependency.versionSupport(), dependency.optional(),
                null, null, Reason.MISSING, "required dependency is not installed: " + dependency.pluginId());
    }

    public static PluginDependencyProblem versionUnsatisfied(PluginDependencyRef dependency, String installedVersion) {
        return new PluginDependencyProblem(dependency.pluginId(), dependency.versionSupport(), dependency.optional(),
                installedVersion, null, Reason.VERSION_UNSATISFIED,
                "required dependency " + dependency.pluginId() + " needs version "
                        + dependency.requirement().display() + ", but visible version is " + installedVersion);
    }

    public static PluginDependencyProblem unavailable(PluginDependencyRef dependency,
                                                      String installedVersion, String status) {
        return new PluginDependencyProblem(dependency.pluginId(), dependency.versionSupport(), dependency.optional(),
                installedVersion, status, Reason.UNAVAILABLE,
                "required dependency " + dependency.pluginId() + " is not active (status " + status + ")");
    }

    public static PluginDependencyProblem catalogMissing(PluginDependencyRef dependency) {
        return new PluginDependencyProblem(dependency.pluginId(), dependency.versionSupport(), dependency.optional(),
                null, null, Reason.CATALOG_MISSING,
                "required dependency is not present in the same catalog: " + dependency.pluginId());
    }

    public static PluginDependencyProblem catalogVersionUnsatisfied(PluginDependencyRef dependency) {
        return new PluginDependencyProblem(dependency.pluginId(), dependency.versionSupport(), dependency.optional(),
                null, null, Reason.CATALOG_VERSION_UNSATISFIED,
                "same catalog has no version satisfying required dependency " + dependency.pluginId()
                        + " " + dependency.requirement().display());
    }

    public static PluginDependencyProblem installFailed(PluginDependencyRef dependency, String outcome) {
        return new PluginDependencyProblem(dependency.pluginId(), dependency.versionSupport(), dependency.optional(),
                null, outcome, Reason.INSTALL_FAILED,
                "required dependency " + dependency.pluginId() + " failed to install: " + outcome);
    }

    public static PluginDependencyProblem cycle(PluginDependencyRef dependency, String cyclePath) {
        return new PluginDependencyProblem(dependency.pluginId(), dependency.versionSupport(), dependency.optional(),
                null, cyclePath, Reason.CYCLE,
                "dependency cycle detected: " + cyclePath);
    }

    public enum Reason {
        MISSING,
        VERSION_UNSATISFIED,
        UNAVAILABLE,
        CATALOG_MISSING,
        CATALOG_VERSION_UNSATISFIED,
        INSTALL_FAILED,
        CYCLE
    }
}
