package top.sywyar.pixivdownload.novelgallery;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.List;

/**
 * novel-gallery 外置插件的 PF4J 主类。展示层贡献由 {@link NovelGalleryPlugin} 声明，业务 Bean 由
 * {@link NovelGalleryPluginConfiguration} 在插件子上下文中装配；小说下载、正文、翻译、合订、
 * TTS 与 AI 听书能力仍由宿主小说核心提供。
 */
public class NovelGalleryPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public List<PixivFeaturePlugin> featurePlugins() {
        return List.of(new NovelGalleryPlugin());
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(NovelGalleryPluginConfiguration.class);
    }
}
