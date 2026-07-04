package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.hash.ArtworkHashService;
import top.sywyar.pixivdownload.novel.controller.NovelDownloadController;
import top.sywyar.pixivdownload.novel.controller.NovelDownloadLegacyForwardController;
import top.sywyar.pixivdownload.novel.controller.NovelPixivProxyController;
import top.sywyar.pixivdownload.novel.download.ScheduledNovelDownloadDelegate;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.QueueTypeRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;

/**
 * 禁用语义（真实 Spring 上下文）：{@code plugins.novel.enabled=false} 时，小说下载托管业务 Bean 缺席、
 * 小说作品类型执行器缺席、小说贡献不注册，但插件 descriptor 仍在安装集合、受管 schema 不变，且其它内置插件
 * 与核心下载链路 Hash 写入不受影响。
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "setup.browser.auto-open=false",
        "plugins.novel.enabled=false"
})
@DisplayName("禁用 novel 插件的真实上下文语义")
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
    private ApplicationContext context;
    @Autowired
    private PluginRegistry pluginRegistry;
    @Autowired
    private NavigationRegistry navigationRegistry;
    @Autowired
    private RouteAccessRegistry routeAccessRegistry;
    @Autowired
    private QueueTypeRegistry queueTypeRegistry;
    @Autowired
    private WebUiSlotRegistry webUiSlotRegistry;
    @Autowired
    private DatabaseSchemaRegistry databaseSchemaRegistry;

    @Test
    @DisplayName("novel 退出活动快照但仍在安装集合 / disabledPlugins")
    void novelLeavesActiveSnapshotButStaysInstalled() {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id).doesNotContain("novel");
        assertThat(pluginRegistry.allPlugins()).extracting(PixivFeaturePlugin::id).contains("novel");
        assertThat(pluginRegistry.disabledPlugins()).extracting(PixivFeaturePlugin::id).containsExactly("novel");
    }

    @Test
    @DisplayName("小说下载托管业务 Bean 全部缺席")
    void novelManagedBeansAbsent() {
        assertThat(context.getBeanNamesForType(NovelDownloadController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(NovelDownloadLegacyForwardController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(NovelPixivProxyController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ScheduledNovelDownloadDelegate.class)).isEmpty();
    }

    @Test
    @DisplayName("小说贡献不注册：导航 / 路由 / 队列类型 / UI 槽位缺席，新旧小说下载 URL 未声明（运行期 404）")
    void novelContributionsAbsent() {
        assertThat(navigationRegistry.navigation())
                .extracting(NavigationRegistry.RegisteredNavigation::pluginId).doesNotContain("novel");
        assertThat(routeAccessRegistry.routes())
                .extracting(RouteAccessRegistry.RegisteredRoute::pluginId).doesNotContain("novel");
        assertThat(queueTypeRegistry.queueTypes())
                .extracting(QueueTypeRegistry.RegisteredQueueType::pluginId).doesNotContain("novel");
        assertThat(webUiSlotRegistry.slots())
                .extracting(WebUiSlotRegistry.RegisteredUiSlot::pluginId).doesNotContain("novel");
        assertThat(routeAccessRegistry.isDeclared("/api/novel/download")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/pixiv/novel/7/meta")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/pixiv/novel-search")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/download/pixiv/novel")).isFalse();
    }

    @Test
    @DisplayName("禁用 novel 不影响其它插件：非 novel 内置插件活动，核心 Hash 写入接缝在场")
    void otherPluginsUnaffected() {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id)
                .contains("core", "plugin-market")
                .doesNotContain("novel");
        assertThat(context.getBeanNamesForType(ArtworkHashService.class)).hasSize(1);
    }

    @Test
    @DisplayName("受管 schema 不随禁用缺失：合并表集合与全启用一致，novels 表仍在")
    void schemaUnchanged() {
        assertThat(databaseSchemaRegistry.mergedSchema().tables().keySet())
                .isEqualTo(DatabaseSchemaRegistry.forBuiltInPlugins().mergedSchema().tables().keySet())
                .contains("novels");
    }
}
