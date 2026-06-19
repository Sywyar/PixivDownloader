package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkKind;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunnerRegistry;
import top.sywyar.pixivdownload.core.hash.ArtworkHashService;
import top.sywyar.pixivdownload.gallery.GalleryController;
import top.sywyar.pixivdownload.novel.controller.NovelGalleryController;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.schedule.OveruseWarningService;
import top.sywyar.pixivdownload.schedule.ScheduleExecutor;
import top.sywyar.pixivdownload.schedule.ScheduleRunQueue;
import top.sywyar.pixivdownload.schedule.ScheduleRunState;
import top.sywyar.pixivdownload.schedule.ScheduleRunner;
import top.sywyar.pixivdownload.schedule.ScheduleService;
import top.sywyar.pixivdownload.schedule.controller.ScheduleController;
import top.sywyar.pixivdownload.schedule.work.ScheduledIllustWorkRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 禁用语义（真实 Spring 上下文）：{@code plugins.download-workbench.enabled=false} 时，schedule 引擎全部
 * 托管 Bean（含唯一 {@code @Scheduled} tick {@link ScheduleRunner}）缺席、后台调度不再运行、插画作品类型执行器
 * 解析为空、下载工作台路由不注册；{@code /redirect} 默认落点回退到其它已启用插件而非指向不可用页面。
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "setup.browser.auto-open=false",
        "plugins.download-workbench.enabled=false"
})
@DisplayName("禁用 download-workbench 插件的真实上下文语义")
class DownloadWorkbenchPluginDisabledContextTest {

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
    private ScheduledWorkRunnerRegistry workRunnerRegistry;
    @Autowired
    private RouteAccessRegistry routeAccessRegistry;
    @Autowired
    private StartupRouteRegistry startupRouteRegistry;

    @Test
    @DisplayName("download-workbench 退出活动快照但仍在安装集合 / disabledPlugins")
    void workbenchLeavesActiveSnapshotButStaysInstalled() {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id).doesNotContain("download-workbench");
        assertThat(pluginRegistry.allPlugins()).extracting(PixivFeaturePlugin::id).contains("download-workbench");
        assertThat(pluginRegistry.disabledPlugins())
                .extracting(PixivFeaturePlugin::id).containsExactly("download-workbench");
    }

    @Test
    @DisplayName("schedule 引擎托管 Bean 全部缺席（含唯一 @Scheduled tick），后台调度不运行")
    void scheduleEngineBeansAbsent() {
        assertThat(context.getBeanNamesForType(ScheduleRunner.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ScheduleExecutor.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ScheduleService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ScheduleController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ScheduledIllustWorkRunner.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ScheduleRunState.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ScheduleRunQueue.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OveruseWarningService.class)).isEmpty();
    }

    @Test
    @DisplayName("作品类型执行器：illust 解析为空、novel 仍在场（小说插件未受影响）")
    void illustWorkRunnerAbsentNovelPresent() {
        assertThat(workRunnerRegistry.resolve(ScheduledWorkKind.ILLUST)).isEmpty();
        assertThat(workRunnerRegistry.resolve(ScheduledWorkKind.NOVEL)).isPresent();
    }

    @Test
    @DisplayName("下载工作台路由不注册：schedule / 下载页 URL 未声明（运行期 404）")
    void workbenchRoutesAbsent() {
        assertThat(routeAccessRegistry.routes())
                .extracting(RouteAccessRegistry.RegisteredRoute::pluginId).doesNotContain("download-workbench");
        assertThat(routeAccessRegistry.isDeclared("/pixiv-batch.html")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/schedule/tasks")).isFalse();
    }

    @Test
    @DisplayName("/redirect 默认落点回退到已启用插件（gallery），不再指向不可用的下载页")
    void redirectFallbackIsSafe() {
        // multi 模式首选 download-workbench；其落点已不注册，按 order 回退到 gallery 的 /pixiv-gallery.html
        //（绝不指向已不可用的下载页 /pixiv-batch.html）。
        assertThat(startupRouteRegistry.resolvePath("download-workbench"))
                .contains("/pixiv-gallery.html")
                .get().isNotEqualTo("/pixiv-batch.html");
    }

    @Test
    @DisplayName("禁用 download-workbench 不影响其它插件：小说 / 画廊 Bean 在场，核心 Hash 写入接缝在场")
    void otherPluginsUnaffected() {
        assertThat(context.getBeanNamesForType(NovelGalleryController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(GalleryController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ArtworkHashService.class)).hasSize(1);
    }
}
