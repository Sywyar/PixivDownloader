package top.sywyar.pixivdownload.plugin.runtime.discovery;

import java.util.Objects;

/**
 * 单个插件包加载 / 启动失败的诊断条目。坏包不影响核心壳启动——失败被隔离捕获成本条目，
 * 而不是向上抛出（见 {@link PluginRuntimeManager}）。
 *
 * @param source 失败来源的可读标识：加载阶段为插件包文件名（如 {@code broken-plugin.jar}），
 *               启动阶段为已解析出的 pluginId
 * @param reason 失败原因（异常信息，缺失时退化为异常类名）
 */
public record PluginLoadFailure(String source, String reason) {

    public PluginLoadFailure {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(reason, "reason");
    }
}
