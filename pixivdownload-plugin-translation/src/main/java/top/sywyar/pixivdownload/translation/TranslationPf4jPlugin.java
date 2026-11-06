package top.sywyar.pixivdownload.translation;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.List;

public class TranslationPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new TranslationPlugin();
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(TranslationPluginConfiguration.class);
    }
}
