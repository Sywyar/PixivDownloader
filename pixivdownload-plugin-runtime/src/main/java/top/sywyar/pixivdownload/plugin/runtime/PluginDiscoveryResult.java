package top.sywyar.pixivdownload.plugin.runtime;

import java.util.List;

/**
 * 一次外置插件发现的结果（不可变）：成功发现的功能插件，以及发现过程中被隔离捕获的诊断。
 * 核心壳据此把外置功能插件接入 {@code PluginRegistry}（{@link #discovered()}），并记录无法发现的诊断
 * （{@link #failures()}）。坏插件 / 不暴露入口契约的插件被收敛为 {@link PluginLoadFailure} 条目，
 * <b>不</b>抛出、<b>不</b>致核心壳启动失败。
 *
 * @param discovered 成功发现的功能插件（来源信息见 {@link DiscoveredFeaturePlugin}）
 * @param failures   发现失败的诊断条目（如主类未实现入口契约、入口方法抛错），按发现顺序排列
 */
public record PluginDiscoveryResult(List<DiscoveredFeaturePlugin> discovered, List<PluginLoadFailure> failures) {

    public PluginDiscoveryResult {
        discovered = List.copyOf(discovered);
        failures = List.copyOf(failures);
    }

    /** 空结果（无插件目录 / 无候选包 / 未加载任何外置插件时使用）。 */
    public static PluginDiscoveryResult empty() {
        return new PluginDiscoveryResult(List.of(), List.of());
    }

    /** 是否有任何发现失败的诊断条目。 */
    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    /** 成功发现的功能插件数量。 */
    public int discoveredCount() {
        return discovered.size();
    }
}
