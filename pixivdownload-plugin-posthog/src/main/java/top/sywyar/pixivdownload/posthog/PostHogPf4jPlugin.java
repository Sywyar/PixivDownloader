package top.sywyar.pixivdownload.posthog;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

/** PF4J entry point for the PostHog adapter plugin. */
public class PostHogPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new PostHogPlugin();
    }
}
