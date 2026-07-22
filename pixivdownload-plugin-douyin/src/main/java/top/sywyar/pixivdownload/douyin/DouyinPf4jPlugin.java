package top.sywyar.pixivdownload.douyin;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.List;

public class DouyinPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new DouyinPlugin();
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(DouyinPluginConfiguration.class);
    }
}
