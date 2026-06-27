package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 未安装 stats 外置插件时的核心默认语义（requirement：未安装 stats 时其导航 / route / static / i18n contribution
 * 不进入活动 registry）。stats 已从内置组合根 {@link BuiltInPlugins} 移出、改为外置 PF4J 插件，故仅由内置插件
 * 构建的各下游注册中心里<b>不应有任何 stats 贡献</b>——其页面 / API 因「未声明即 404」不可达。
 *
 * <p>真实「放入外置 stats jar 后启动加载、以 EXTERNAL 来源接入并注册 stats 贡献」由
 * {@link StatsExternalPluginIntegrationTest} 用真实插件 jar 覆盖。统计事实底层表（{@code statistics}）归核心、
 * 不随 stats 是否安装而增删（核心 schema 由 {@code CoreSchemaContribution} 声明、与 stats 无关），故此处不涉及。
 */
@DisplayName("未安装 stats 外置插件：内置注册中心无任何 stats 贡献")
class StatsNotInstalledByDefaultTest {

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
