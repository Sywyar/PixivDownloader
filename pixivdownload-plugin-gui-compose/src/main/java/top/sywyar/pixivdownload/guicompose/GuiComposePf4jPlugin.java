package top.sywyar.pixivdownload.guicompose;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

/** 由 Gradle 构建的 Compose 桌面 provider 的 PF4J 入口。 */
public final class GuiComposePf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new GuiComposePlugin();
    }
}
