package top.sywyar.pixivdownload.guicompose;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

/** PF4J entry point for the Gradle-built Compose desktop provider. */
public final class GuiComposePf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new GuiComposePlugin();
    }
}
