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
 * 必选插件语义（真实 Spring 上下文）：下载工作台是必选插件，即便配置
 * {@code plugins.download-workbench.enabled=false} 也<b>被忽略</b>——插件仍进入活动快照、schedule 引擎全部
 * 托管 Bean（含唯一 {@code @Scheduled} tick {@link ScheduleRunner}）照常装配、插画作品类型执行器解析得到、
 * 下载工作台路由照常声明、{@code /redirect} 默认落点仍为下载页。
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "setup.browser.auto-open=false",
        "plugins.download-workbench.enabled=false"
})
@DisplayName("下载工作台必选插件的真实上下文语义（禁用开关被忽略）")
class DownloadWorkbenchRequiredContextTest {

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
    @DisplayName("禁用开关被忽略：download-workbench 仍在活动快照、不在 disabledPlugins")
    void workbenchStaysActiveDespiteDisableFlag() {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id).contains("download-workbench");
        assertThat(pluginRegistry.allPlugins()).extracting(PixivFeaturePlugin::id).contains("download-workbench");
        assertThat(pluginRegistry.disabledPlugins())
                .extracting(PixivFeaturePlugin::id).doesNotContain("download-workbench");
    }

    @Test
    @DisplayName("schedule 引擎托管 Bean 全部在场（含唯一 @Scheduled tick），后台调度照常运行")
    void scheduleEngineBeansPresent() {
        assertThat(context.getBeanNamesForType(ScheduleRunner.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ScheduleExecutor.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ScheduleService.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ScheduleController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ScheduledIllustWorkRunner.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ScheduleRunState.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ScheduleRunQueue.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(OveruseWarningService.class)).hasSize(1);
    }

    @Test
    @DisplayName("作品类型执行器：illust 解析得到（插画下载执行器在场）")
    void illustWorkRunnerPresent() {
        assertThat(workRunnerRegistry.resolve(ScheduledWorkKind.ILLUST)).isPresent();
    }

    @Test
    @DisplayName("下载工作台路由照常声明：schedule / 下载页 URL 已声明")
    void workbenchRoutesDeclared() {
        assertThat(routeAccessRegistry.routes())
                .extracting(RouteAccessRegistry.RegisteredRoute::pluginId).contains("download-workbench");
        assertThat(routeAccessRegistry.isDeclared("/pixiv-batch.html")).isTrue();
        assertThat(routeAccessRegistry.isDeclared("/api/schedule/tasks")).isTrue();
    }

    @Test
    @DisplayName("/redirect 默认落点仍为下载工作台页（其落点照常注册）")
    void redirectResolvesToWorkbench() {
        assertThat(startupRouteRegistry.resolvePath("download-workbench"))
                .contains("/pixiv-batch.html");
    }
}
