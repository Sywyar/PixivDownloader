package top.sywyar.pixivdownload.novel;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.List;

/**
 * novel 外置插件的 PF4J 主类。贡献由 {@link NovelPlugin} 声明，业务 Bean 由
 * {@link NovelPluginConfiguration} 在插件子上下文中装配。
 */
public class NovelPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public List<PixivFeaturePlugin> featurePlugins() {
        return List.of(new NovelPlugin());
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(NovelPluginConfiguration.class);
    }
}
