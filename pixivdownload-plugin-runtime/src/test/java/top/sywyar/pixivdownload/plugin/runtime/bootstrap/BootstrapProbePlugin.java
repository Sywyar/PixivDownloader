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
 * 真实可加载的外置探针插件（仅测试用）：镜像 recovery-sentinel 的最小外置插件形态——继承 {@link Plugin}、实现入口契约
 * {@link PixivPluginProvider}、暴露最小功能插件 {@link BootstrapProbeFeaturePlugin}，使其能通过
 * {@code PluginRuntimeManager} 的发布形态校验并被真实加载 / 启动。
 *
 * <p>PF4J 加载即（懒）构造、start / stop 即记录，供 bootstrap 端到端探针经文件标记跨插件 classloader 观测
 * load / start / stop 次数。标记文件路径经系统属性 {@code bootstrap.probe.marker} 传入（JVM 全局、与 classloader 无关）。
 */
public class BootstrapProbePlugin extends Plugin implements PixivPluginProvider {

    public BootstrapProbePlugin(PluginWrapper wrapper) {
        super(wrapper);
        record("load");
    }

    @Override
    public void start() {
        record("start");
    }

    @Override
    public void stop() {
        record("stop");
    }

    @Override
    public List<PixivFeaturePlugin> featurePlugins() {
        return List.of(new BootstrapProbeFeaturePlugin());
    }

    private void record(String event) {
        String markerPath = System.getProperty("bootstrap.probe.marker");
        if (markerPath == null || markerPath.isBlank()) {
            return;
        }
        try {
            Files.writeString(Path.of(markerPath), event + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // best-effort：探针记录失败不影响 PF4J 生命周期。
        }
    }
}
