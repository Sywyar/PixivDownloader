package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.hash.ArtworkHashService;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.novel.controller.NovelDownloadController;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;

/**
 * 缺失 / 禁用语义（真实 Spring 上下文）：gallery / novel-gallery 已是外置插件，外置包缺失时即便写入禁用开关也不注册贡献；
 * 同时尝试禁用核心插件
 * （{@code plugins.core.enabled=false}）。验证：
 * <ul>
 *   <li>核心 Hash 写入服务（{@link ArtworkHashService}，核心 root 扫描、非插件托管）<b>仍在场</b>——
 *       下载后即时算 Hash 不随 duplicate 外置插件缺席；</li>
 *   <li>gallery / novel-gallery / duplicate 缺失时其路由、静态资源、i18n、导航与维护任务不注入，核心维护任务仍在场；</li>
 *   <li>{@code plugins.core.enabled=false} 被忽略——核心插件不可禁用，核心 Bean 始终在场；</li>
 *   <li>未受影响的 novel 下载 Bean 仍在场；外置 download-workbench 不属于 core 壳内置上下文。</li>
 * </ul>
 * （统计 stats、gallery 与 duplicate 都是外置 PF4J 插件、不在内置清单内：其安装后接入语义由外置加载测试覆盖。）
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-absent",
        "setup.browser.auto-open=false",
        "plugins.gallery.enabled=false",
        "plugins.novel-gallery.enabled=false",
        "plugins.core.enabled=false"
})
@DisplayName("gallery/novel-gallery 外置缺失且尝试禁用 core 的真实上下文语义")
class FeaturePluginsDisabledContextTest {

    static {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config");
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state");
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data");
        System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, "target/test-runtime/plugins-absent");
    }

    @AfterAll
    static void tearDownRuntimeDirs() {
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY);
    }

    @Autowired
    private ApplicationContext context;
    @Autowired
    private PluginRegistry pluginRegistry;
    @Autowired
    private RouteAccessRegistry routeAccessRegistry;
    @Autowired
    private StaticResourceRegistry staticResourceRegistry;
    @Autowired
    private WebI18nBundleRegistry webI18nBundleRegistry;
    @Autowired
    private NavigationRegistry navigationRegistry;

    @Test
    @DisplayName("gallery/novel-gallery/duplicate 未安装，core 仍在场（core 配置被忽略）")
    void disabledFeaturesLeaveSnapshotCoreStays() {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id)
                .contains("core", "novel")
                .doesNotContain("gallery", "novel-gallery", "duplicate");
        assertThat(pluginRegistry.disabledPlugins()).extracting(PixivFeaturePlugin::id)
                .doesNotContain("gallery", "novel-gallery", "duplicate");
        // core 永不可禁用：plugins.core.enabled=false 被忽略，核心插件仍活动、descriptor Bean 在场。
        assertThat(pluginRegistry.find("core")).isPresent();
        assertThat(context.getBeanNamesForType(CorePlugin.class)).hasSize(1);
    }

    @Test
    @DisplayName("gallery/novel-gallery 外置包缺失时 route/static/i18n/nav/ui 贡献缺席，duplicate 外置 Bean 不在 core-only 上下文")
    void missingExternalFeatureContributionsAbsent() {
        assertThat(routeAccessRegistry.routes())
                .extracting(RouteAccessRegistry.RegisteredRoute::pluginId)
                .doesNotContain("gallery", "novel-gallery", "duplicate");
        assertThat(routeAccessRegistry.isDeclared("/pixiv-gallery.html")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/gallery/artworks")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/pixiv-novel-gallery.html")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/pixiv-novel.html")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/gallery/novels")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/gallery/novel/7")).isFalse();
        assertThat(staticResourceRegistry.resources())
                .extracting(StaticResourceRegistry.RegisteredStaticResource::pluginId)
                .doesNotContain("gallery", "novel-gallery", "duplicate");
        assertThat(webI18nBundleRegistry.resolve("gallery")).isNull();
        assertThat(webI18nBundleRegistry.resolve("artwork")).isNull();
        assertThat(webI18nBundleRegistry.resolve("novel-gallery")).isNull();
        assertThat(navigationRegistry.navigation())
                .extracting(NavigationRegistry.RegisteredNavigation::pluginId)
                .doesNotContain("gallery", "novel-gallery", "duplicate");
        assertThat(context.getBeanDefinitionNames())
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("duplicate"));
    }

    @Test
    @DisplayName("核心 Hash 写入服务在场：ArtworkHashService（核心 root 扫描）不随 duplicate 缺席")
    void coreHashWriteSeamStaysPresent() {
        assertThat(context.getBeanNamesForType(ArtworkHashService.class)).hasSize(1);
        // 它是核心服务、不属 duplicate 插件托管范围：duplicate 未安装时下载后即时算 Hash 的核心链路仍然在场。
    }

    @Test
    @DisplayName("duplicate 回填维护任务不注入协调器，核心维护任务仍在场")
    void duplicateMaintenanceTaskNotInjectedCoreTasksStay() {
        assertThat(context.getBeansOfType(MaintenanceTask.class).values())
                .extracting(MaintenanceTask::name)
                .doesNotContain("duplicate-hash-backfill")
                .contains("database-optimize", "guest-invite-cleanup");
    }

    @Test
    @DisplayName("缺失上述插件不影响 novel 下载 Bean")
    void unrelatedPluginsUnaffected() {
        assertThat(context.getBeanNamesForType(NovelDownloadController.class)).hasSize(1);
    }
}
