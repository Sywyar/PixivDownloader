package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.registry.web.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.StaticResourceRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gallery-tools 外置插件不编入宿主核心壳的边界。默认发行包会在构建期把 artifact 预置到 {@code plugins/}，
 * 但统计与疑似重复贡献仍只随该外置插件接入。
 *
 * <p>真实加载由 {@link GalleryToolsExternalPluginIntegrationTest} 覆盖。统计事实与图片 Hash 索引仍归核心，
 * 不随 gallery-tools 是否安装而增删。
 */
@DisplayName("gallery-tools 不编入宿主核心壳")
class GalleryToolsNotBuiltIntoHostTest {

    private final PluginRegistry registry = new PluginRegistry(BuiltInPlugins.createAll());

    @Test
    @DisplayName("内置组合根与活动 / 安装快照均不含 gallery-tools")
    void builtInRegistryHasNoGalleryTools() {
        assertThat(BuiltInPlugins.createAll()).extracting(PixivFeaturePlugin::id).doesNotContain("gallery-tools");
        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).doesNotContain("gallery-tools");
        assertThat(registry.allPlugins()).extracting(PixivFeaturePlugin::id).doesNotContain("gallery-tools");
        assertThat(registry.find("gallery-tools")).isEmpty();
    }

    @Test
    @DisplayName("route / navigation / static / i18n 注册中心均无画廊工具贡献")
    void downstreamRegistriesHaveNoGalleryToolsContribution() {
        assertThat(new RouteAccessRegistry(registry).routes())
                .noneMatch(r -> r.pluginId().equals("gallery-tools"));
        assertThat(new RouteAccessRegistry(registry).routes())
                .extracting(r -> r.route().pathPattern())
                .doesNotContain(
                        "/api/stats/**", "/pixiv-stats.html", "/pixiv-stats/**",
                        "/api/duplicates/**", "/pixiv-duplicates.html", "/pixiv-duplicates/**");
        assertThat(new NavigationRegistry(registry).navigation())
                .noneMatch(n -> n.pluginId().equals("gallery-tools"));
        assertThat(new StaticResourceRegistry(registry).resources())
                .noneMatch(s -> s.pluginId().equals("gallery-tools"));
        assertThat(new StaticResourceRegistry(registry).resources())
                .extracting(s -> s.contribution().publicPathPrefix())
                .doesNotContain(
                        "/pixiv-stats/", "/pixiv-stats.html",
                        "/pixiv-duplicates/", "/pixiv-duplicates.html");
        assertThat(new WebI18nBundleRegistry(registry).resolve("gallery-tools")).isNull();
        assertThat(new WebI18nBundleRegistry(registry).resolve("stats")).isNull();
        assertThat(new WebI18nBundleRegistry(registry).resolve("duplicates")).isNull();
    }
}
