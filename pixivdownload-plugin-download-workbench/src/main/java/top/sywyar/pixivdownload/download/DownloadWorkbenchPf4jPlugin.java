package top.sywyar.pixivdownload.download;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.schedule.ScheduleHostPluginConfiguration;

import java.util.List;

/**
 * PF4J entry point for the official required download-workbench plugin.
 */
public class DownloadWorkbenchPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new DownloadWorkbenchPlugin();
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(DownloadWorkbenchPluginConfiguration.class, ScheduleHostPluginConfiguration.class);
    }
}
