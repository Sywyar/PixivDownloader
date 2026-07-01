package top.sywyar.pixivdownload.plugin.lifecycle;

/**
 * 外置插件运行期热启停 / quiesce 状态机操作失败：非法阶段流转（如对未启动插件 quiesce）、未知 pluginId、
 * 核心注册中心接入失败等。携带清晰诊断（当前态 → 目标态 / 失败原因），供可观测排查。
 */
public class PluginLifecycleException extends RuntimeException {

    public PluginLifecycleException(String message) {
        super(message);
    }

    public PluginLifecycleException(String message, Throwable cause) {
        super(message, cause);
    }
}
