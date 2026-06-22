package top.sywyar.pixivdownload.plugin;

import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.duplicate.DuplicatePlugin;
import top.sywyar.pixivdownload.gallery.GalleryPlugin;
import top.sywyar.pixivdownload.novel.NovelPlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.schedule.ScheduleHostPlugin;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 内置插件组合根清单。供 Spring 上下文之外的入口（如 GUI 启动期的 schema 检查）
 * 取得与运行期一致的内置插件 contribution 集合。
 * <p>
 * 必须与各 {@code XxxPluginConfiguration} 注册进 {@link PluginRegistry} 的内置插件集合
 * 保持一致（由 {@code RegisteredPluginsTest} 的镜像用例守护）。<b>仅含随 boot jar 编译进来的内置插件</b>；
 * 外置插件（如 stats）从 {@code plugins/} 目录由 PF4J 加载、经发现桥接接入 {@link PluginRegistry}（来源
 * {@code EXTERNAL}），不在本清单内。
 */
public final class BuiltInPlugins {

    private BuiltInPlugins() {}

    /** 按固定顺序创建全部内置插件实例（插件类均为无 Spring 注解的 POJO）。 */
    public static List<PixivFeaturePlugin> createAll() {
        return List.of(
                new CorePlugin(),
                new DownloadWorkbenchPlugin(),
                new ScheduleHostPlugin(),
                new GalleryPlugin(),
                new NovelPlugin(),
                new DuplicatePlugin());
    }

    /** 必选插件 id 集合（{@link PixivFeaturePlugin#required()}），随内置清单固定。 */
    private static final Set<String> REQUIRED_IDS = createAll().stream()
            .filter(PixivFeaturePlugin::required)
            .map(PixivFeaturePlugin::id)
            .collect(Collectors.toUnmodifiableSet());

    /** 全部内置插件 id 集合，随内置清单固定。 */
    private static final Set<String> BUILT_IN_IDS = createAll().stream()
            .map(PixivFeaturePlugin::id)
            .collect(Collectors.toUnmodifiableSet());

    /**
     * 给定 id 是否为必选插件（不可经 {@code plugins.<id>.enabled} 禁用）。必选语义在运行期由
     * {@link PluginRegistry}（注册期 {@code required() || toggles.isEnabled}）强制；本方法供守卫
     * {@code PluginApiDependencyGuardTest} 据此断言「必选插件的业务 Bean 不得标
     * {@code @ConditionalOnPluginEnabled}」——plugin-runtime 的 {@code OnPluginEnabledCondition} 已不再
     * 回指本类判定必选性（只读开关），该不变量改由上述守卫固化。
     */
    public static boolean isRequired(String pluginId) {
        return pluginId != null && REQUIRED_IDS.contains(pluginId);
    }

    /**
     * 给定 id 是否为内置插件。用于导航排序的来源层级判定：内置插件（核心 / 必选基础页面 / 可选功能插件）
     * 恒排在第三方插件之前——第三方插件即便声明很小的 priority，也只能追加在内置项之后。
     */
    public static boolean isBuiltIn(String pluginId) {
        return pluginId != null && BUILT_IN_IDS.contains(pluginId);
    }
}
