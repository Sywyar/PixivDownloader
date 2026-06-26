package top.sywyar.pixivdownload.plugin.market;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.PluginRegistry;
import top.sywyar.pixivdownload.plugin.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 禁用语义（真实 Spring 上下文）：{@code plugins.plugin-market.enabled=false} 时，插件市场托管业务 Bean
 * （{@link PluginMarketController} / {@link PluginMarketService}）缺席、market web 贡献（路由 / 静态资源 / i18n / 导航）
 * 随插件退出活动快照而撤销、{@code /api/plugin-market/**} 与市场页 URL「未声明即 404」，但 descriptor 仍在安装集合
 * （disabledPlugins）、其它插件不受影响——以此实证市场后端 / 页面 / 路由 / 静态 / i18n / 导航均<b>由 plugin-market 插件拥有</b>、
 * 随其生命周期撤销。
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "setup.browser.auto-open=false",
        "plugins.plugin-market.enabled=false"
})
@DisplayName("禁用 plugin-market 插件的真实上下文语义")
class PluginMarketPluginDisabledContextTest {

    static {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config");
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state");
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data");
    }

    @AfterAll
    static void tearDownRuntimeDirs() {
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
    }

    @Autowired
    private ApplicationContext context;
    @Autowired
    private PluginRegistry pluginRegistry;
    @Autowired
    private NavigationRegistry navigationRegistry;
    @Autowired
    private RouteAccessRegistry routeAccessRegistry;
    @Autowired
    private StaticResourceRegistry staticResourceRegistry;
    @Autowired
    private WebI18nBundleRegistry webI18nBundleRegistry;

    @Test
    @DisplayName("plugin-market 退出活动快照但仍在安装集合 / disabledPlugins")
    void marketLeavesActiveSnapshotButStaysInstalled() {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id).doesNotContain("plugin-market");
        assertThat(pluginRegistry.allPlugins()).extracting(PixivFeaturePlugin::id).contains("plugin-market");
        assertThat(pluginRegistry.disabledPlugins()).extracting(PixivFeaturePlugin::id).contains("plugin-market");
    }

    @Test
    @DisplayName("插件市场托管业务 Bean 全部缺席")
    void marketManagedBeansAbsent() {
        assertThat(context.getBeanNamesForType(PluginMarketController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(PluginMarketService.class)).isEmpty();
    }

    @Test
    @DisplayName("market 贡献不注册：导航 / 路由 / 静态资源 / i18n 缺席，市场页与 API URL 未声明（运行期 404）")
    void marketContributionsAbsent() {
        assertThat(navigationRegistry.navigation())
                .extracting(NavigationRegistry.RegisteredNavigation::pluginId).doesNotContain("plugin-market");
        assertThat(routeAccessRegistry.routes())
                .extracting(RouteAccessRegistry.RegisteredRoute::pluginId).doesNotContain("plugin-market");
        assertThat(staticResourceRegistry.resources())
                .extracting(StaticResourceRegistry.RegisteredStaticResource::pluginId).doesNotContain("plugin-market");
        assertThat(webI18nBundleRegistry.supportedNamespaces()).doesNotContain("plugin-market");
        // 路由「未声明即 404」：市场页 / 页面静态资源 / 后端 API 都不再被任何声明覆盖。
        assertThat(routeAccessRegistry.isDeclared("/plugin-market.html")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/plugin-market/plugin-market.css")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/plugin-market/repositories", HttpMethod.GET)).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/plugin-market/official/demo/1.0.0/install", HttpMethod.POST)).isFalse();
    }

    @Test
    @DisplayName("禁用 plugin-market 不影响其它插件：catalog 引擎核心基础设施仍在场、插件管理后端仍在场")
    void otherComponentsUnaffected() {
        // catalog 引擎是核心基础设施（非 market 托管）：禁用 market 后仍在场（仅 market web 面撤销）。
        assertThat(context.getBeanNamesForType(
                top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(
                top.sywyar.pixivdownload.plugin.catalog.PluginCatalogAcquisitionService.class)).hasSize(1);
        // 插件管理后端（/api/plugins/**）归核心、与市场正交，不受影响。
        assertThat(context.getBeanNamesForType(
                top.sywyar.pixivdownload.plugin.PluginManagementController.class)).hasSize(1);
    }
}
