package top.sywyar.pixivdownload.plugin.api.resource;

/** 资源作用域的稳定失败结果；不携带插件异常或资源引用。 */
public final class PluginResourceException extends IllegalStateException {
    /** 可由调用方直接判断的失败原因。 */
    public enum Reason {
        /** 当前作用域或 owner 已关闭准入。 */
        CLOSED,
        /** 仍有工厂、回调或关闭操作正在执行。 */
        BUSY,
        /** 一个或多个资源尚未成功关闭，可重试。 */
        CLOSE_FAILED
    }
    /** 稳定原因码，不保留插件异常引用。 */
    private final Reason reason;

    /** @param reason 失败原因 */
    public PluginResourceException(Reason reason) {
        super("PLUGIN_RESOURCE_" + java.util.Objects.requireNonNull(reason, "reason").name());
        this.reason = reason;
    }

    /** @return 稳定失败原因 */
    public Reason reason() {
        return reason;
    }
}
