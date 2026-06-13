package top.sywyar.pixivdownload.plugin;

import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.duplicate.DuplicatePlugin;
import top.sywyar.pixivdownload.gallery.GalleryPlugin;
import top.sywyar.pixivdownload.novel.NovelPlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.stats.StatsPlugin;

import java.util.List;

/**
 * 内置插件组合根清单。供 Spring 上下文之外的入口（如 GUI 启动期的 schema 检查）
 * 取得与运行期一致的插件 contribution 集合。
 * <p>
 * 必须与各 {@code XxxPluginConfiguration} 注册进 {@link PluginRegistry} 的插件集合
 * 保持一致（由 {@code RegisteredPluginsTest} 的镜像用例守护）。
 */
public final class BuiltInPlugins {

    private BuiltInPlugins() {}

    /** 按固定顺序创建全部内置插件实例（插件类均为无 Spring 注解的 POJO）。 */
    public static List<PixivFeaturePlugin> createAll() {
        return List.of(
                new CorePlugin(),
                new DownloadWorkbenchPlugin(),
                new GalleryPlugin(),
                new NovelPlugin(),
                new StatsPlugin(),
                new DuplicatePlugin());
    }
}
