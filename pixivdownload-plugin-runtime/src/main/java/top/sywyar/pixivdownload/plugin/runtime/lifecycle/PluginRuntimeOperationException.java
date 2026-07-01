package top.sywyar.pixivdownload.plugin.runtime.lifecycle;

/** 单包 PF4J 物理生命周期失败。失败时管理器不会伪造目标阶段。 */
public class PluginRuntimeOperationException extends RuntimeException {

    public PluginRuntimeOperationException(String message) {
        super(message);
    }

    public PluginRuntimeOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
