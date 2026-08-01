package top.sywyar.pixivdownload.plugin.api.task;

import java.util.concurrent.RejectedExecutionException;

/**
 * 插件 owner 已停止接收新运行期任务。
 */
public final class PluginRuntimeTaskRejectedException extends RejectedExecutionException {

    public PluginRuntimeTaskRejectedException() {
        super("plugin runtime task admission is closed");
    }
}
