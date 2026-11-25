package top.sywyar.pixivdownload.plugin.lifecycle;

import top.sywyar.pixivdownload.plugin.management.PluginManagementErrorCode;

/** 带稳定管理错误分类的生命周期编排失败。 */
public class ClassifiedPluginLifecycleException extends PluginLifecycleException {

    public record ReloadRollback(boolean restored, String loadedVersion, PluginRuntimePhase phase) {}

    private final PluginManagementErrorCode code;
    private final ReloadRollback rollback;

    public ClassifiedPluginLifecycleException(PluginManagementErrorCode code, String message) {
        this(code, message, null);
    }

    public ClassifiedPluginLifecycleException(PluginManagementErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.rollback = null;
    }

    public ClassifiedPluginLifecycleException(ReloadRollback rollback, Throwable cause) {
        super(rollback.restored() ? "Reload failed and the previous generation was restored"
                : "Reload failed and the previous generation could not be restored", cause);
        this.code = rollback.restored() ? PluginManagementErrorCode.RELOAD_ROLLED_BACK : PluginManagementErrorCode.RELOAD_ROLLBACK_FAILED;
        this.rollback = rollback;
    }

    public ReloadRollback rollback() {
        return rollback;
    }

    public PluginManagementErrorCode code() {
        return code;
    }
}
