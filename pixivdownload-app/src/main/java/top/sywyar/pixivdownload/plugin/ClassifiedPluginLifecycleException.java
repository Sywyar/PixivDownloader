package top.sywyar.pixivdownload.plugin;

/** 带稳定管理错误分类的生命周期编排失败。 */
public class ClassifiedPluginLifecycleException extends PluginLifecycleException {

    private final PluginManagementErrorCode code;

    public ClassifiedPluginLifecycleException(PluginManagementErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ClassifiedPluginLifecycleException(PluginManagementErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public PluginManagementErrorCode code() {
        return code;
    }
}
