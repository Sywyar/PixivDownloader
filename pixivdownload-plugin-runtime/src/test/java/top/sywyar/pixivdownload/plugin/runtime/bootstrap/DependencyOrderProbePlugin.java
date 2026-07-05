package top.sywyar.pixivdownload.plugin.runtime.bootstrap;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 依赖排序探针插件：同一插件类可被多个测试包复用，功能插件 id 跟随 PF4J 包 id。
 */
public class DependencyOrderProbePlugin extends Plugin implements PixivPluginProvider {

    private final String pluginId;

    public DependencyOrderProbePlugin(PluginWrapper wrapper) {
        super(wrapper);
        this.pluginId = wrapper.getPluginId();
        record("load");
    }

    @Override
    public void start() {
        record("start");
    }

    @Override
    public List<PixivFeaturePlugin> featurePlugins() {
        return List.of(new DependencyOrderProbeFeaturePlugin(pluginId));
    }

    private void record(String event) {
        String markerPath = System.getProperty("dependency.order.probe.marker");
        if (markerPath == null || markerPath.isBlank()) {
            return;
        }
        try {
            Files.writeString(Path.of(markerPath), event + ":" + pluginId + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // best-effort：探针记录失败不影响 PF4J 生命周期。
        }
    }
}
