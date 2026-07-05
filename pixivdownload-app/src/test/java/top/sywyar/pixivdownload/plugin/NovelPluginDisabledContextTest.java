package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.hash.ArtworkHashService;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.QueueTypeRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * core-only 语义（真实 Spring 上下文）：novel 外置插件缺失时，小说下载、Pixiv 小说代理、
 * 小说队列类型、计划任务小说执行器、小说画廊与阅读页贡献均缺席；核心 schema 和其它核心 Bean 不受影响。
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "setup.browser.auto-open=false",
        "plugins.novel.enabled=false"
})
@DisplayName("novel 外置插件缺失的真实上下文语义")
class NovelPluginDisabledContextTest {

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
    private PluginRegistry pluginRegistry;
    @Autowired
    private NavigationRegistry navigationRegistry;
    @Autowired
    private RouteAccessRegistry routeAccessRegistry;
    @Autowired
    private StaticResourceRegistry staticResourceRegistry;
    @Autowired
    private QueueTypeRegistry queueTypeRegistry;
    @Autowired
    private WebUiSlotRegistry webUiSlotRegistry;
    @Autowired
    private DatabaseSchemaRegistry databaseSchemaRegistry;
    @Autowired
    private ArtworkHashService artworkHashService;

    @Test
    @DisplayName("novel 未安装时不进入活动 / 安装 / 禁用快照")
    void novelMissingFromSnapshots() {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id).doesNotContain("novel");
        assertThat(pluginRegistry.allPlugins()).extracting(PixivFeaturePlugin::id).doesNotContain("novel");
        assertThat(pluginRegistry.disabledPlugins()).extracting(PixivFeaturePlugin::id).doesNotContain("novel");
    }

    @Test
    @DisplayName("小说贡献不注册：路由 / 静态资源 / 导航 / 队列类型 / UI 槽位缺席")
    void novelContributionsAbsent() {
        assertThat(navigationRegistry.navigation())
                .extracting(NavigationRegistry.RegisteredNavigation::pluginId).doesNotContain("novel");
        assertThat(routeAccessRegistry.routes())
                .extracting(RouteAccessRegistry.RegisteredRoute::pluginId).doesNotContain("novel");
        assertThat(staticResourceRegistry.resources())
                .extracting(StaticResourceRegistry.RegisteredStaticResource::pluginId).doesNotContain("novel");
        assertThat(queueTypeRegistry.queueTypes())
                .extracting(QueueTypeRegistry.RegisteredQueueType::pluginId).doesNotContain("novel");
        assertThat(webUiSlotRegistry.slots())
                .extracting(WebUiSlotRegistry.RegisteredUiSlot::pluginId).doesNotContain("novel");
        assertThat(routeAccessRegistry.isDeclared("/api/novel/download")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/pixiv/novel/7/meta")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/pixiv/novel-search")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/download/pixiv/novel")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/pixiv-novel-gallery.html")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/gallery/novels")).isFalse();
    }

    @Test
    @DisplayName("novel 缺失不影响其它核心 Bean 与核心 schema")
    void coreUnaffected() {
        assertThat(databaseSchemaRegistry.mergedSchema().tables().keySet())
                .isEqualTo(DatabaseSchemaRegistry.forBuiltInPlugins().mergedSchema().tables().keySet())
                .contains("novels");
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id)
                .contains("core", "plugin-market")
                .doesNotContain("novel");
        assertThat(artworkHashService).isNotNull();
    }
}
