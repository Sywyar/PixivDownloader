package top.sywyar.pixivdownload.gallerytools;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.duplicate.DuplicatePluginConfiguration;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.stats.StatsPluginConfiguration;

import java.util.List;

/**
 * gallery-tools 外置插件的唯一 PF4J 主类。描述贡献由 {@link GalleryToolsPlugin} 声明，统计与疑似重复的
 * 业务 Bean 则由各自配置类在同一个插件子上下文中装配。
 */
public class GalleryToolsPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new GalleryToolsPlugin();
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(StatsPluginConfiguration.class, DuplicatePluginConfiguration.class);
    }
}
