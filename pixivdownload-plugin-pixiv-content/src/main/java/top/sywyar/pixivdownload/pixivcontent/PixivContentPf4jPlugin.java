package top.sywyar.pixivdownload.pixivcontent;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

/** PF4J entry point for the Pixiv content API. */
public final class PixivContentPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new PixivContentPlugin();
    }
}
