package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.Audience;

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

    private static List<String> navIds(PluginRegistry registry) {
        return new NavigationRegistry(registry).navigation().stream()
                .map(registered -> registered.navigation().id())
                .toList();
    }

    @Test
    @DisplayName("禁用各功能插件：其导航项从 NavigationRegistry 消失（前端据此让跨插件入口自然隐藏）")
    void disablingFeaturePluginsDropsTheirNavigation() {
        assertThat(navIds(registryDisabling("gallery"))).doesNotContain("gallery");
        assertThat(navIds(registryDisabling("novel"))).doesNotContain("novel");
        assertThat(navIds(registryDisabling("stats"))).doesNotContain("stats");
        assertThat(navIds(registryDisabling("duplicate"))).doesNotContain("duplicate");
    }

    private static List<String> pageSectionOwners(PluginRegistry registry) {
        return new PageSectionRegistry(registry).sections().stream()
                .map(PageSectionRegistry.RegisteredSection::pluginId)
                .toList();
    }

    @Test
    @DisplayName("禁用画廊：其页面区块从 PageSectionRegistry 消失（统计页借用的视图 / 收藏夹区块随插件自然隐藏）")
    void disablingGalleryDropsItsPageSections() {
        // 启用时画廊向统计页贡献区块；禁用后这些区块不再注册——统计页只声明空 slot，无残留画廊业务入口。
        assertThat(pageSectionOwners(allEnabled())).contains("gallery");
        assertThat(pageSectionOwners(registryDisabling("gallery"))).doesNotContain("gallery");
    }

    private static List<String> drilldownOwners(PluginRegistry registry) {
        return new DrilldownRegistry(registry).drilldowns().stream()
                .map(DrilldownRegistry.RegisteredDrilldown::pluginId)
                .toList();
    }

    private static List<String> drilldownIds(PluginRegistry registry) {
        return new DrilldownRegistry(registry).drilldowns().stream()
                .map(registered -> registered.drilldown().id())
                .toList();
    }

    @Test
    @DisplayName("禁用画廊：其语义下钻贡献从 DrilldownRegistry 消失（统计页 Top 作者 / 热门标签随插件回到纯展示）")
    void disablingGalleryDropsItsDrilldowns() {
        // 启用时画廊向统计页两个语义 placement 贡献下钻模板；禁用后这些贡献不再注册——统计页 /api/drilldowns 对应
        // placement 无内容、下钻链接自然消失（回到纯展示），统计页不需要知道画廊是否存在。
        assertThat(drilldownOwners(allEnabled())).contains("gallery");
        assertThat(drilldownIds(allEnabled())).contains("gallery-stats-author", "gallery-stats-tag");
        assertThat(drilldownOwners(registryDisabling("gallery"))).doesNotContain("gallery");
        assertThat(drilldownIds(registryDisabling("gallery")))
                .doesNotContain("gallery-stats-author", "gallery-stats-tag");
    }

    private static List<String> landingOwners(PluginRegistry registry) {
        return new LandingRegistry(registry).landings().stream()
                .map(LandingRegistry.RegisteredLanding::pluginId)
                .toList();
    }

    @Test
    @DisplayName("禁用功能插件：其落点从 LandingRegistry 消失，邀请落点按落点优先级自动回退到其它已启用插件")
    void disablingFeaturePluginsDropsTheirLandings() {
        // 禁用画廊：画廊落点不再注册，受邀访客邀请落点回退到小说（priority 30，仍启用）。
        assertThat(landingOwners(registryDisabling("gallery"))).doesNotContain("gallery").contains("novel");
        assertThat(new LandingRegistry(registryDisabling("gallery")).resolve(Audience.INVITED_GUEST))
                .contains("/pixiv-novel-gallery.html");
        // 画廊 + 小说都禁用：无受邀访客落点（调用方兜底回登录页）。
        assertThat(new LandingRegistry(registryDisabling("gallery", "novel")).resolve(Audience.INVITED_GUEST))
                .isEmpty();
    }

    @Test
    @DisplayName("禁用单个功能插件不影响其它插件导航（跨插件独立）")
    void disablingOnePluginKeepsOtherNavigation() {
        List<String> ids = navIds(registryDisabling("gallery"));
        // 仅 gallery 入口消失；下载工作台 / 监控 / 小说 / 统计 / 疑似重复 / 邀请码管理仍在。
        assertThat(ids).contains(
                "download-workbench", "monitor", "novel", "stats", "duplicate", "invite-manage");
    }

    @Test
    @DisplayName("下载工作台为必选插件：即便配置 enabled=false 也仍贡献下载页导航（无法被关闭）")
    void requiredDownloadWorkbenchKeepsNavigationEvenWhenDisabled() {
        assertThat(navIds(registryDisabling("download-workbench"))).contains("download-workbench");
    }

    @Test
    @DisplayName("core 始终贡献监控 / 邀请码管理基础入口（不可禁用）")
    void coreAlwaysContributesBaseNavigation() {
        assertThat(navIds(allEnabled())).contains("monitor", "invite-manage");
    }
}
