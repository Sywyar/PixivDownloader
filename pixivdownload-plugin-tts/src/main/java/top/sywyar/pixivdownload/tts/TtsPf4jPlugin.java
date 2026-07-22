package top.sywyar.pixivdownload.tts;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.List;

public class TtsPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new TtsPlugin();
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(TtsPluginConfiguration.class);
    }
}
