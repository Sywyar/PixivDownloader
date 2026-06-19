package top.sywyar.pixivdownload;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.duplicate.DuplicateController;
import top.sywyar.pixivdownload.duplicate.DuplicateHashBackfillTask;
import top.sywyar.pixivdownload.gallery.GalleryController;
import top.sywyar.pixivdownload.novel.controller.NovelGalleryController;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;
import top.sywyar.pixivdownload.stats.StatsController;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "setup.browser.auto-open=false"
})
class PixivDownloadApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

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

    @Test
    void contextLoads() {
    }

    /**
     * 自定义的三个专用 {@code TaskExecutor} bean 会触发 Spring Boot 的
     * {@code applicationTaskExecutor}（{@code @ConditionalOnMissingBean(Executor.class)}）不再自动创建，
     * 而 Spring MVC 异步（SseEmitter 进度推送）依赖名为 {@code applicationTaskExecutor} 的执行器。
     * 这里锁定：默认执行器与三个专用执行器必须同时存在，防止再次顶掉默认池导致状态同步/流式进度失效。
     */
    @Test
    void asyncExecutorsAreRegistered() {
        assertThat(applicationContext.containsBean("applicationTaskExecutor")).isTrue();
        assertThat(applicationContext.containsBean("downloadTaskExecutor")).isTrue();
        assertThat(applicationContext.containsBean("novelDownloadTaskExecutor")).isTrue();
        assertThat(applicationContext.containsBean("archiveTaskExecutor")).isTrue();
    }

    /**
     * 插件托管 controller 经 {@code @PluginManagedBean} 排除出根包扫描、由各插件
     * Configuration 以 {@code @Bean} 提供。这里锁定：context 中
     * 该类型 Bean 恰好一个（既没被扫描重复注册、也没因排除过滤器配置错误而丢失），
     * 且 Spring MVC 仍能识别其 handler 方法、URL 映射零变化。
     */
    @Test
    @DisplayName("插件托管 controller 恰好注册一次且 MVC 映射不变")
    void pluginManagedControllerRegisteredExactlyOnce() {
        assertThat(applicationContext.getBeanNamesForType(StatsController.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(DuplicateController.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(GalleryController.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(NovelGalleryController.class)).hasSize(1);
        RequestMappingHandlerMapping handlerMapping = applicationContext
                .getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        assertThat(mappedPatterns(handlerMapping, "/api/stats/dashboard")).isTrue();
        assertThat(mappedPatterns(handlerMapping, "/api/duplicates/groups")).isTrue();
        assertThat(mappedPatterns(handlerMapping, "/api/gallery/artworks")).isTrue();
        assertThat(mappedPatterns(handlerMapping, "/api/gallery/novels")).isTrue();
    }

    private static boolean mappedPatterns(RequestMappingHandlerMapping handlerMapping, String pattern) {
        return handlerMapping.getHandlerMethods().keySet().stream()
                .anyMatch(info -> info.getPathPatternsCondition() != null
                        && info.getPathPatternsCondition().getPatternValues().contains(pattern));
    }

    /**
     * {@code DuplicateHashBackfillTask} 收敛为 {@code @Bean} 提供后仍须被
     * {@code MaintenanceCoordinator} 的 {@code List<MaintenanceTask>} 注入发现——
     * 这里锁定按类型收集维护任务时 duplicate-hash-backfill 仍在。
     */
    @Test
    @DisplayName("插件托管维护任务仍被按类型发现")
    void pluginManagedMaintenanceTaskStillDiscovered() {
        assertThat(applicationContext.getBeanNamesForType(DuplicateHashBackfillTask.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(MaintenanceTask.class).values())
                .extracting(MaintenanceTask::name)
                .contains("duplicate-hash-backfill");
    }

}
