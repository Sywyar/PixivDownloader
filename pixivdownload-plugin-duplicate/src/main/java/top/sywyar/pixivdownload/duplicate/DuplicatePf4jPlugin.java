package top.sywyar.pixivdownload.duplicate;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.List;

/**
 * duplicate 外置插件的 PF4J 主类。功能贡献由 {@link DuplicatePlugin} 声明，业务 Bean 由
 * {@link DuplicatePluginConfiguration} 在插件子上下文中装配；核心 Hash 写入服务和 schema 仍由宿主提供。
 */
public class DuplicatePf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new DuplicatePlugin();
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(DuplicatePluginConfiguration.class);
    }
}
