package top.sywyar.pixivdownload.mail;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.List;

public class MailPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new MailPlugin();
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(MailPluginConfiguration.class);
    }
}
