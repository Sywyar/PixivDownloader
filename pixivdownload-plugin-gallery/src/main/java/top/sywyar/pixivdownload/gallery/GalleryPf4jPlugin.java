package top.sywyar.pixivdownload.gallery;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.List;

/**
 * gallery 外置插件的 PF4J 主类。展示层贡献由 {@link GalleryPlugin} 声明，业务 Bean 由
 * {@link GalleryPluginConfiguration} 在插件子上下文中装配；下载事实、元数据、资产、缩略图、
 * 收藏 / 作者 / 系列共享能力和删除编排仍由宿主核心提供。
 */
public class GalleryPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public List<PixivFeaturePlugin> featurePlugins() {
        return List.of(new GalleryPlugin());
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(GalleryPluginConfiguration.class);
    }
}
