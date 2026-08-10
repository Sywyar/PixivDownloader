package top.sywyar.pixivdownload.notificationbase;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.List;

public class NotificationPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new NotificationPlugin();
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(NotificationPluginConfiguration.class);
    }
}
