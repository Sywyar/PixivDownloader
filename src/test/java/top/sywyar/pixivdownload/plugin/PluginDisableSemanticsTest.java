package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插件禁用语义：禁用功能插件 → 其导航 / 路由等贡献不再注册（经活动快照排除），但受管 schema 不变
 * （经 {@code allPlugins()} 合并）、插件仍在 {@code allPlugins()} 中，且不影响其它插件。
 */
@DisplayName("插件禁用语义")
class PluginDisableSemanticsTest {

    private static PluginRegistry registryDisabling(String... ids) {
        PluginToggleProperties toggles = new PluginToggleProperties();
        for (String id : ids) {
            PluginToggleProperties.PluginToggle off = new PluginToggleProperties.PluginToggle();
            off.setEnabled(false);
            toggles.put(id, off);
        }
        return new PluginRegistry(BuiltInPlugins.createAll(), toggles);
    }

    private static PluginRegistry allEnabled() {
        return new PluginRegistry(BuiltInPlugins.createAll());
    }

    private static List<String> routeOwners(PluginRegistry registry) {
        return new RouteAccessRegistry(registry).routes().stream()
                .map(RouteAccessRegistry.RegisteredRoute::pluginId)
                .toList();
    }

    @Test
    @DisplayName("禁用 gallery：其导航 / 路由不再注册，但仍在 allPlugins，受管 schema 不变")
    void disablingGalleryDropsContributionsButKeepsSchema() {
        PluginRegistry disabled = registryDisabling("gallery");

        assertThat(disabled.plugins()).extracting(PixivFeaturePlugin::id).doesNotContain("gallery");
        assertThat(disabled.allPlugins()).extracting(PixivFeaturePlugin::id).contains("gallery");
        assertThat(disabled.disabledPlugins()).extracting(PixivFeaturePlugin::id).containsExactly("gallery");

        assertThat(new NavigationRegistry(disabled).navigation())
                .extracting(NavigationRegistry.RegisteredNavigation::pluginId)
                .doesNotContain("gallery");
        assertThat(routeOwners(disabled)).doesNotContain("gallery");

        // 受管 schema 经 allPlugins 合并，禁用功能插件不改变表集合（核心数据 schema 不丢）。
        assertThat(new DatabaseSchemaRegistry(disabled).mergedSchema().tables().keySet())
                .isEqualTo(new DatabaseSchemaRegistry(allEnabled()).mergedSchema().tables().keySet());
    }

    @Test
    @DisplayName("禁用画廊后下载工作台仍注册其路由（下载页可运行）")
    void disablingGalleryKeepsDownloadWorkbench() {
        assertThat(routeOwners(registryDisabling("gallery"))).contains("download-workbench");
    }

    @Test
    @DisplayName("下载工作台为必选插件：即便配置 enabled=false 也仍进入活动快照并注册其路由（无法被关闭）")
    void downloadWorkbenchIsRequiredAndCannotBeDisabled() {
        PluginRegistry registry = registryDisabling("download-workbench");

        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).contains("download-workbench");
        assertThat(registry.disabledPlugins())
                .extracting(PixivFeaturePlugin::id).doesNotContain("download-workbench");
        assertThat(routeOwners(registry)).contains("download-workbench");
    }

    @Test
    @DisplayName("禁用 stats / duplicate：受管 schema 不变，其路由不再注册")
    void disablingStatsAndDuplicateKeepsSchemaIntact() {
        PluginRegistry disabled = registryDisabling("stats", "duplicate");

        assertThat(new DatabaseSchemaRegistry(disabled).mergedSchema().tables().keySet())
                .isEqualTo(new DatabaseSchemaRegistry(allEnabled()).mergedSchema().tables().keySet());
        assertThat(routeOwners(disabled)).doesNotContain("stats", "duplicate");
    }
}
