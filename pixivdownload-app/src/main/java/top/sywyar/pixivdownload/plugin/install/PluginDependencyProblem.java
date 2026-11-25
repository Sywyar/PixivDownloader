package top.sywyar.pixivdownload.plugin.install;

import io.swagger.v3.oas.annotations.media.Schema;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;

/**
 * 插件依赖未满足的结构化诊断。用于安装拒绝、市场自动依赖安装失败、运行期启用阻断等场景。
 *
 * @param pluginId         被依赖插件 id
 * @param versionSupport   依赖声明中的版本要求
 * @param optional         是否为可选依赖
 * @param installedVersion 当前可见版本（缺失时为 {@code null}）
 * @param status           与原因对应的状态、依赖方 id 或诊断上下文（可空）
 * @param reason           稳定原因码
 * @param detail           英文诊断说明（排错用，非用户文案）
 */
public record PluginDependencyProblem(
        String pluginId,
        String versionSupport,
        boolean optional,
        @HttpApiNullable String installedVersion,
        @HttpApiNullable @Schema(examples = {"DISABLED", "BUILT_IN", "REJECTED_INCOMPATIBLE"}) String status,
        Reason reason,
        String detail) {

    static final String HEADLESS_SKIPPED = "HEADLESS_SKIPPED";
    static final String AWAITING_RESTART = "AWAITING_RESTART";

    public boolean availableAfterRestart() {
        return reason == Reason.UNAVAILABLE && deferredStatus(status);
    }

    static boolean deferredStatus(String status) {
        return AWAITING_RESTART.equals(status) || HEADLESS_SKIPPED.equals(status);
    }

    static PluginDependencyProblem builtIn(PluginDependencyRef dependency) {
        return new PluginDependencyProblem(dependency.pluginId(), dependency.versionSupport(), dependency.optional(),
                null, "BUILT_IN", Reason.UNAVAILABLE, "required dependency " + dependency.pluginId()
                        + " is built into the host and cannot supply a plugin package dependency");
    }

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

    public static PluginDependencyProblem dependentConflict(String dependentId, PluginDependencyRef dependency,
                                                            String installedVersion, String candidateVersion) {
        return new PluginDependencyProblem(dependency.pluginId(), dependency.versionSupport(), false,
                installedVersion, dependentId, Reason.DEPENDENT_CONFLICT,
                dependentId + " requires " + dependency.pluginId() + " " + dependency.requirement().display()
                        + (candidateVersion == null ? ", which would be removed" : ", but the candidate version is " + candidateVersion));
    }

    public static PluginDependencyProblem retirementBlocked(String consumer, PluginDependencyRef dependency, String version) {
        return new PluginDependencyProblem(dependency.pluginId(), dependency.versionSupport(), false, version,
                consumer, Reason.DEPENDENT_CONFLICT, "loaded plugin " + consumer + " requires " + dependency.pluginId()
                        + " and prevents its unload");
    }

    static PluginDependencyProblem restartAdmissionRejected(PluginDependencyRef dependency, String version) {
        return new PluginDependencyProblem(dependency.pluginId(), dependency.versionSupport(), dependency.optional(),
                version, "ADMISSION_REJECTED", Reason.UNAVAILABLE,
                "installed artifact for required dependency " + dependency.pluginId() + " failed restart admission");
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
        DEPENDENT_CONFLICT,
        UNAVAILABLE,
        CATALOG_MISSING,
        CATALOG_VERSION_UNSATISFIED,
        INSTALL_FAILED,
        CYCLE
    }
}
