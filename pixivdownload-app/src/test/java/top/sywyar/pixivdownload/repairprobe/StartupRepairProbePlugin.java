package top.sywyar.pixivdownload.repairprobe;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

/** 真实 PF4J 启动失败包；构造版本 7 失败，版本 8 可供管理入口替换修复。 */
public class StartupRepairProbePlugin extends Plugin implements PixivPluginProvider {
    public StartupRepairProbePlugin(PluginWrapper wrapper) { super(wrapper); }

    @Override
    public void start() {
        if (getWrapper().getDescriptor().getVersion().startsWith("7.")) {
            throw new IllegalStateException("fixture startup failure");
        }
    }

    @Override
    public PixivFeaturePlugin featurePlugin() { return new Feature(getWrapper().getPluginId()); }

    public record Feature(String id) implements PixivFeaturePlugin {
        @Override public String displayName() { return "plugin.name"; }
        @Override public String description() { return "plugin.summary"; }
        @Override public top.sywyar.pixivdownload.plugin.api.plugin.PluginKind kind() {
            return top.sywyar.pixivdownload.plugin.api.plugin.PluginKind.FEATURE;
        }
    }
}
