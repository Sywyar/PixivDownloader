package top.sywyar.pixivdownload.runtimeprobe;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

/** 隔离 worker 静态纯值测试入口。 */
public final class IsolatedStaticProbePlugin extends Plugin implements PixivPluginProvider {

    static {
        try {
            Class.forName(
                    "top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager",
                    false,
                    ClassLoader.getSystemClassLoader());
            throw new AssertionError("isolated worker inherited the host runtime classpath");
        } catch (ClassNotFoundException expected) {
            // worker 系统 classloader 只能看到最小执行内核与稳定契约。
        }
    }

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new IsolatedStaticProbeFeaturePlugin();
    }
}
