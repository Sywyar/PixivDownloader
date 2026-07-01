package top.sywyar.pixivdownload.ai;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.List;

public class AiPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public List<PixivFeaturePlugin> featurePlugins() {
        return List.of(new AiPlugin());
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(AiPluginConfiguration.class);
    }
}
