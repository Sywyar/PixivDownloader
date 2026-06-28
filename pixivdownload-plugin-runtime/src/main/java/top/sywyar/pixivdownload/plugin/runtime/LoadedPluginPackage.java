package top.sywyar.pixivdownload.plugin.runtime;

import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 一个已由 PF4J 加载的包代际快照。该对象可能携带插件实例、配置类和 classloader，调用方必须在物理卸载前丢弃。
 */
public record LoadedPluginPackage(
        String packageId,
        Path artifactPath,
        String version,
        long generation,
        PluginRuntimePackagePhase phase,
        PluginInventory inventory,
        List<PluginContextModule> contextModules) {

    public LoadedPluginPackage {
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(artifactPath, "artifactPath");
        Objects.requireNonNull(phase, "phase");
        inventory = inventory != null ? inventory : PluginInventory.empty();
        contextModules = contextModules != null ? List.copyOf(contextModules) : List.of();
    }

    public LoadedPluginPackage withPhase(PluginRuntimePackagePhase nextPhase) {
        return new LoadedPluginPackage(packageId, artifactPath, version, generation, nextPhase,
                inventory, contextModules);
    }
}
