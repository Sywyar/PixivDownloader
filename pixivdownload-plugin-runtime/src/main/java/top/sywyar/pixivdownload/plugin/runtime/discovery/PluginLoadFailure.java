package top.sywyar.pixivdownload.plugin.runtime.discovery;

import java.util.Objects;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;

/**
 * 单个插件包加载 / 启动失败的诊断条目。坏包不影响核心壳启动——失败被隔离捕获成本条目，
 * 而不是向上抛出（见 {@link PluginRuntimeManager}）。
 *
 * @param source 失败来源的可读标识：加载阶段为插件包文件名（如 {@code broken-plugin.jar}），
 *               启动阶段为已解析出的 pluginId
 * @param reason          失败原因（异常信息，缺失时退化为异常类名）
 * @param status          失败对应的稳定状态
 * @param phase           失败发生的运行阶段
 * @param generation      插件 generation；无法解析时为 0
 * @param version         已解析版本；无法解析时为空
 * @param occurrenceCount 同 generation 的累计发生次数
 * @param logPath         有界本地诊断日志路径；没有时为空
 */
public record PluginLoadFailure(String source,
                                String reason,
                                PluginStatus status,
                                String phase,
                                long generation,
                                String version,
                                int occurrenceCount,
                                String logPath) {

    public PluginLoadFailure(String source, String reason) {
        this(source, reason, PluginStatus.FAILED, "admission", 0L, null, 1, null);
    }

    public PluginLoadFailure {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(phase, "phase");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        if (occurrenceCount <= 0) {
            throw new IllegalArgumentException("occurrenceCount must be positive");
        }
    }
}
