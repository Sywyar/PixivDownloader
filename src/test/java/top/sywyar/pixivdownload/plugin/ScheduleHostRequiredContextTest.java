package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.schedule.OveruseWarningService;
import top.sywyar.pixivdownload.schedule.ScheduleExecutor;
import top.sywyar.pixivdownload.schedule.ScheduleRunQueue;
import top.sywyar.pixivdownload.schedule.ScheduleRunState;
import top.sywyar.pixivdownload.schedule.ScheduleRunner;
import top.sywyar.pixivdownload.schedule.ScheduleService;
import top.sywyar.pixivdownload.schedule.controller.ScheduleController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 必选插件语义（真实 Spring 上下文）：计划任务宿主是必选插件，即便配置 {@code plugins.schedule.enabled=false}
 * 也<b>被忽略</b>——插件仍进入活动快照、不进 {@code disabledPlugins}、调度安全壳全部托管 Bean（含唯一
 * {@code @Scheduled} tick {@link ScheduleRunner} 与 {@link ScheduleExecutor} / {@link ScheduleService} / 控制器）
 * 照常装配、后台调度照常运行、{@code /api/schedule/**} 路由照常声明且归 {@code schedule} 宿主。
 * <p>
 * 注意：{@code plugins.schedule.enabled}（插件开关）与引擎设置命名空间 {@code schedule.*}（如
 * {@code schedule.enabled} tick 开关）是两套独立配置——本测试仅以前者验证必装语义，不触碰后者。
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "setup.browser.auto-open=false",
        "plugins.schedule.enabled=false"
})
@DisplayName("计划任务宿主必选插件的真实上下文语义（禁用开关被忽略）")
class ScheduleHostRequiredContextTest {

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
    private RouteAccessRegistry routeAccessRegistry;

    @Test
    @DisplayName("禁用开关被忽略：schedule 仍在活动快照、不在 disabledPlugins")
    void scheduleStaysActiveDespiteDisableFlag() {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id).contains("schedule");
        assertThat(pluginRegistry.allPlugins()).extracting(PixivFeaturePlugin::id).contains("schedule");
        assertThat(pluginRegistry.disabledPlugins())
                .extracting(PixivFeaturePlugin::id).doesNotContain("schedule");
    }

    @Test
    @DisplayName("调度安全壳托管 Bean 全部在场（含唯一 @Scheduled tick），后台调度照常运行")
    void scheduleEngineBeansPresent() {
        assertThat(context.getBeanNamesForType(ScheduleRunner.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ScheduleExecutor.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ScheduleService.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ScheduleController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ScheduleRunState.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ScheduleRunQueue.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(OveruseWarningService.class)).hasSize(1);
    }

    @Test
    @DisplayName("计划任务管理路由照常声明：/api/schedule/** 已声明、owner=schedule")
    void scheduleRoutesDeclared() {
        assertThat(routeAccessRegistry.isDeclared("/api/schedule/tasks")).isTrue();
        assertThat(routeAccessRegistry.routes())
                .extracting(RouteAccessRegistry.RegisteredRoute::pluginId).contains("schedule");
    }
}
