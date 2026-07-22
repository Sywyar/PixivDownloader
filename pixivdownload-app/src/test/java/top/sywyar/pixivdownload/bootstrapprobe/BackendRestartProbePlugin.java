package top.sywyar.pixivdownload.bootstrapprobe;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 真实可加载的外置探针插件（仅测试用）：继承 PF4J Plugin、实现入口契约 {@link PixivPluginProvider}、暴露最小功能插件
 * {@link BackendRestartProbeFeaturePlugin}，使其能通过 {@code PluginRuntimeManager} 的发布形态校验并被真实加载 / 启动。
 *
 * <p>PF4J 加载即（懒）构造、start / stop 即记录，供端到端重启探针经文件标记跨插件 classloader 观测 load / start / stop
 * 次数。标记文件路径经系统属性 {@code bootstrap.probe.marker} 传入（JVM 全局、与 classloader 无关）。
 *
 * <p>镜像 {@code pixivdownload-plugin-runtime} 测试源的 {@code BootstrapProbePlugin}（同一 load / start / stop 标记协议），
 * 非 app 侧的独立生命周期实现——app 测试无法访问 plugin-runtime 的测试类，故在同协议下重新声明同一夹具。
 */
public class BackendRestartProbePlugin extends org.pf4j.Plugin implements PixivPluginProvider {

    public BackendRestartProbePlugin(org.pf4j.PluginWrapper wrapper) {
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
    public PixivFeaturePlugin featurePlugin() {
        return new BackendRestartProbeFeaturePlugin();
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
