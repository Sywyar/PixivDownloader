package top.sywyar.pixivdownload.plugin.runtime.discovery;

import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次外置插件清点（inventory）的结果（不可变）：每个已启动外置插件包贡献的功能插件安装条目
 * （{@link PluginInstallation}，含描述符 + 基线状态 + classloader + 实例）以及包级加载 / 发现失败诊断。
 *
 * <p>这是发现桥接 {@link PixivPluginDiscoveryBridge#inspect} 的完整产出，既供核心壳的插件状态报告消费，也经
 * {@link #toDiscoveryResult()} 投影为既有的 {@link PluginDiscoveryResult}（仅可接入的安装条目进入
 * {@code discovered}、不兼容条目并入 {@code failures}），供 {@code PluginRegistry} 接入——<b>不兼容插件因此被拒绝接入</b>。
 *
 * @param installations 各功能插件安装条目（含可接入与不兼容两类）
 * @param failures      包级加载 / 发现失败诊断（坏包、主类未实现入口契约、入口方法抛错等，被隔离不致命）
 */
public record PluginInventory(List<PluginInstallation> installations, List<PluginLoadFailure> failures) {

    public PluginInventory {
        installations = List.copyOf(installations);
        failures = List.copyOf(failures);
    }

    /** 空清点结果（无插件目录 / 无候选包 / 未加载任何外置插件时使用）。 */
    public static PluginInventory empty() {
        return new PluginInventory(List.of(), List.of());
    }

    /**
     * 投影为既有的发现结果：仅可接入（{@link PluginInstallation#registrable()}）的安装条目进入
     * {@code discovered}；不兼容的安装条目并入 {@code failures}（拒绝接入并给出兼容性诊断），与包级失败一起返回。
     */
    public PluginDiscoveryResult toDiscoveryResult() {
        List<DiscoveredFeaturePlugin> discovered = new ArrayList<>();
        List<PluginLoadFailure> allFailures = new ArrayList<>(failures);
        for (PluginInstallation installation : installations) {
            if (installation.registrable()) {
                discovered.add(new DiscoveredFeaturePlugin(
                        installation.descriptor().sourcePluginId(), 0L,
                        installation.plugin(),
                        installation.classLoader()));
            } else if (installation.status() == PluginStatus.INCOMPATIBLE) {
                allFailures.add(new PluginLoadFailure(installation.id(),
                        "incompatible: requires core API " + installation.descriptor().requires().display()
                                + ", but core provides " + PluginApiVersion.VERSION));
            }
        }
        return new PluginDiscoveryResult(discovered, allFailures);
    }
}
