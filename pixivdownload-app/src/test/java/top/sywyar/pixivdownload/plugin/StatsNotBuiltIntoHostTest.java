package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * stats 外置插件不编入宿主核心壳的边界。默认发行包会在构建期把 stats artifact 预置到 {@code plugins/}，但 stats
 * 仍已从内置组合根 {@link BuiltInPlugins} 移出，因此仅由内置插件构建的各下游注册中心里<b>不应有任何 stats 贡献</b>。
 *
 * <p>真实「放入外置 stats jar 后启动加载、以 EXTERNAL 来源接入并注册 stats 贡献」由
 * {@link StatsExternalPluginIntegrationTest} 用真实插件 jar 覆盖。统计事实底层表（{@code statistics}）归核心、
 * 不随 stats 是否安装而增删（核心 schema 由 {@code CoreSchemaContribution} 声明、与 stats 无关），故此处不涉及。
 */
@DisplayName("stats 不编入宿主核心壳：内置注册中心无任何 stats 贡献")
class StatsNotBuiltIntoHostTest {

    private final PluginRegistry registry = new PluginRegistry(BuiltInPlugins.createAll());

    @Test
    @DisplayName("内置组合根不含 stats，活动 / 安装快照里都没有 stats")
    void builtInRegistryHasNoStats() {
        assertThat(BuiltInPlugins.createAll()).extracting(PixivFeaturePlugin::id).doesNotContain("stats");
        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).doesNotContain("stats");
        assertThat(registry.allPlugins()).extracting(PixivFeaturePlugin::id).doesNotContain("stats");
        assertThat(registry.find("stats")).isEmpty();
    }

    @Test
    @DisplayName("route / navigation / static / i18n 注册中心均无 stats 贡献")
    void downstreamRegistriesHaveNoStatsContribution() {
        assertThat(new RouteAccessRegistry(registry).routes())
                .noneMatch(r -> r.pluginId().equals("stats"));
        assertThat(new RouteAccessRegistry(registry).routes())
                .extracting(r -> r.route().pathPattern())
                .doesNotContain("/api/stats/**", "/pixiv-stats.html", "/pixiv-stats/**");
        assertThat(new NavigationRegistry(registry).navigation())
                .noneMatch(n -> n.pluginId().equals("stats"));
        assertThat(new StaticResourceRegistry(registry).resources())
                .noneMatch(s -> s.pluginId().equals("stats"));
        assertThat(new StaticResourceRegistry(registry).resources())
                .extracting(s -> s.contribution().publicPathPrefix())
                .doesNotContain("/pixiv-stats/", "/pixiv-stats.html");
        assertThat(new WebI18nBundleRegistry(registry).resolve("stats")).isNull();
    }
}
