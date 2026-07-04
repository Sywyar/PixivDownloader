package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.entry.GuiWebEntryContributionAggregator;
import top.sywyar.pixivdownload.gui.onboarding.GuiOnboardingContributionAggregator;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.Audience;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.registry.DrilldownRegistry;
import top.sywyar.pixivdownload.plugin.registry.LandingRegistry;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PageSectionRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;

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
        return new PluginRegistry(withGalleryAndNovelGallery(BuiltInPlugins.createAll()), toggles);
    }

    private static PluginRegistry allEnabled() {
        return new PluginRegistry(withGalleryAndNovelGallery(BuiltInPlugins.createAll()));
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
        assertThat(GuiWebEntryContributionAggregator.from(disabled).statusActions())
                .extracting(action -> action.pluginId())
                .doesNotContain("gallery");
        assertThat(GuiOnboardingContributionAggregator.from(disabled).steps())
                .extracting(step -> step.pluginId())
                .doesNotContain("gallery");
        assertThat(routeOwners(disabled)).doesNotContain("gallery");

        // 受管 schema 经 allPlugins 合并，禁用功能插件不改变表集合（核心数据 schema 不丢）。
        assertThat(new DatabaseSchemaRegistry(disabled).mergedSchema().tables().keySet())
                .isEqualTo(new DatabaseSchemaRegistry(allEnabled()).mergedSchema().tables().keySet());
    }

    @Test
    @DisplayName("禁用画廊不会在 core-only 内置快照中引入外置下载工作台路由")
    void disablingGalleryKeepsDownloadWorkbenchExternal() {
        assertThat(routeOwners(registryDisabling("gallery"))).doesNotContain("download-workbench");
    }

    @Test
    @DisplayName("download-workbench 已外置：内置插件开关不会把它伪造成 built-in 活动插件")
    void downloadWorkbenchIsExternalToBuiltInSnapshot() {
        PluginRegistry registry = registryDisabling("download-workbench");

        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).doesNotContain("download-workbench");
        assertThat(registry.allPlugins()).extracting(PixivFeaturePlugin::id).doesNotContain("download-workbench");
        assertThat(registry.disabledPlugins())
                .extracting(PixivFeaturePlugin::id).doesNotContain("download-workbench");
        assertThat(routeOwners(registry)).doesNotContain("download-workbench");
    }

    @Test
    @DisplayName("禁用 gallery / novel-gallery / novel：受管 schema 不变，其路由不再注册")
    void disablingGalleryAndNovelKeepsSchemaIntact() {
        PluginRegistry disabled = registryDisabling("gallery", "novel-gallery", "novel");

        assertThat(new DatabaseSchemaRegistry(disabled).mergedSchema().tables().keySet())
                .isEqualTo(new DatabaseSchemaRegistry(allEnabled()).mergedSchema().tables().keySet());
        assertThat(routeOwners(disabled)).doesNotContain("gallery", "novel-gallery", "novel");
    }

    private static List<String> navIds(PluginRegistry registry) {
        return new NavigationRegistry(registry).navigation().stream()
                .map(registered -> registered.navigation().id())
                .toList();
    }

    @Test
    @DisplayName("禁用各功能插件：其导航项从 NavigationRegistry 消失（前端据此让跨插件入口自然隐藏）")
    void disablingFeaturePluginsDropsTheirNavigation() {
        assertThat(navIds(registryDisabling("gallery")))
                .doesNotContain("gallery", "gallery-gui-open", "gallery-invite-manage-back");
        assertThat(navIds(registryDisabling("novel-gallery")))
                .doesNotContain("novel-gallery", "novel-type-switch");
    }

    @Test
    @DisplayName("禁用 gallery：其 GUI Web 入口与欢迎页步骤从聚合快照消失")
    void disablingGalleryDropsGuiEntriesAndOnboardingSteps() {
        assertThat(GuiWebEntryContributionAggregator.from(allEnabled()).statusActions())
                .extracting(action -> action.id())
                .contains("gallery-gui-open");
        assertThat(GuiWebEntryContributionAggregator.from(registryDisabling("gallery")).statusActions())
                .extracting(action -> action.id())
                .doesNotContain("gallery-gui-open");

        assertThat(GuiOnboardingContributionAggregator.from(allEnabled()).steps())
                .extracting(step -> step.stepId())
                .contains("local-gallery-guide");
        assertThat(GuiOnboardingContributionAggregator.from(registryDisabling("gallery")).steps())
                .extracting(step -> step.stepId())
                .doesNotContain("local-gallery-guide");
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
        // 禁用画廊：画廊落点不再注册，受邀访客邀请落点回退到小说画廊（priority 30，仍启用）。
        assertThat(landingOwners(registryDisabling("gallery")))
                .doesNotContain("gallery").contains("novel-gallery");
        assertThat(new LandingRegistry(registryDisabling("gallery")).resolve(Audience.INVITED_GUEST))
                .contains("/pixiv-novel-gallery.html");
        // 画廊 + 小说画廊都禁用：无受邀访客落点（调用方兜底回登录页）。
        assertThat(new LandingRegistry(registryDisabling("gallery", "novel-gallery")).resolve(Audience.INVITED_GUEST))
                .isEmpty();
    }

    @Test
    @DisplayName("禁用单个功能插件不影响其它插件导航（跨插件独立）")
    void disablingOnePluginKeepsOtherNavigation() {
        List<String> ids = navIds(registryDisabling("gallery"));
        // 仅 gallery 入口消失；监控 / 小说画廊 / 邀请码管理仍在（download-workbench/stats/duplicate 已外置、不在内置导航）。
        assertThat(ids).contains(
                "monitor", "novel-gallery", "invite-manage");
    }

    @Test
    @DisplayName("download-workbench 已外置：内置插件开关不会注册下载页导航")
    void downloadWorkbenchToggleDoesNotCreateBuiltInNavigation() {
        assertThat(navIds(registryDisabling("download-workbench"))).doesNotContain("download-workbench");
    }

    @Test
    @DisplayName("duplicate 已外置：内置插件开关不会把它伪造成 built-in 活动插件")
    void duplicateIsExternalToBuiltInSnapshot() {
        PluginRegistry registry = registryDisabling("duplicate");

        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).doesNotContain("duplicate");
        assertThat(registry.allPlugins()).extracting(PixivFeaturePlugin::id).doesNotContain("duplicate");
        assertThat(registry.disabledPlugins()).extracting(PixivFeaturePlugin::id).doesNotContain("duplicate");
        assertThat(routeOwners(registry)).doesNotContain("duplicate");
        assertThat(navIds(registry)).doesNotContain("duplicate");
    }

    @Test
    @DisplayName("core 始终贡献监控 / 邀请码管理基础入口（不可禁用）")
    void coreAlwaysContributesBaseNavigation() {
        assertThat(navIds(allEnabled())).contains("monitor", "invite-manage");
    }

    private static List<PixivFeaturePlugin> withGalleryAndNovelGallery(List<PixivFeaturePlugin> plugins) {
        java.util.ArrayList<PixivFeaturePlugin> out = new java.util.ArrayList<>(plugins);
        out.add(new TestGalleryPlugin());
        out.add(new TestNovelGalleryPlugin());
        return out;
    }
}
