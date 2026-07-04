package top.sywyar.pixivdownload;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.hash.ArtworkHashService;
import top.sywyar.pixivdownload.novel.controller.NovelDownloadController;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-absent",
        "setup.browser.auto-open=false"
})
class PixivDownloadApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

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
     * 内置 novel 下载 controller 经 {@code @PluginManagedBean} 排除出根包扫描、由插件
     * Configuration 以 {@code @Bean} 提供。这里锁定小说下载入口仍在 context 中恰好一个
     * （既没被扫描重复注册、也没因排除过滤器配置错误而丢失），且 Spring MVC 仍能识别其 handler 方法。
     * gallery / novel-gallery 已外置，不在 core shell 根上下文中断言。
     */
    @Test
    @DisplayName("内置 novel 下载 controller 恰好注册一次且 MVC 映射不变")
    void pluginManagedControllerRegisteredExactlyOnce() {
        assertThat(applicationContext.getBeanNamesForType(NovelDownloadController.class)).hasSize(1);
        RequestMappingHandlerMapping handlerMapping = applicationContext
                .getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        assertThat(mappedPatterns(handlerMapping, "/api/novel/download")).isTrue();
    }

    private static boolean mappedPatterns(RequestMappingHandlerMapping handlerMapping, String pattern) {
        return handlerMapping.getHandlerMethods().keySet().stream()
                .anyMatch(info -> info.getPathPatternsCondition() != null
                        && info.getPathPatternsCondition().getPatternValues().contains(pattern));
    }

    @Test
    @DisplayName("默认 core-only：核心 Hash 写入服务在场，duplicate 外置 Bean 不在场")
    void coreHashSeamPresentWhenDuplicatePluginMissing() {
        assertThat(applicationContext.getBeanNamesForType(ArtworkHashService.class)).hasSize(1);
        assertThat(applicationContext.getBeanDefinitionNames())
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("duplicate"));
    }

    @Test
    @DisplayName("core-only 默认维护任务不包含 duplicate 回填")
    void duplicateMaintenanceTaskAbsentWhenPluginMissing() {
        assertThat(applicationContext.getBeansOfType(MaintenanceTask.class).values())
                .extracting(MaintenanceTask::name)
                .doesNotContain("duplicate-hash-backfill")
                .contains("database-optimize", "guest-invite-cleanup");
    }

}
