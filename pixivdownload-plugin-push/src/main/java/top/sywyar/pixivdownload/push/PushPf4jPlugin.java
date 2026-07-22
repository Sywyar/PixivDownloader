package top.sywyar.pixivdownload.push;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.List;

public class PushPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new PushPlugin();
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(PushPluginConfiguration.class);
    }
}
