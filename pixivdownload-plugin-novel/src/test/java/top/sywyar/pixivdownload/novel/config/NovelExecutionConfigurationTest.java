package top.sywyar.pixivdownload.novel.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.plugin.runtime.stream.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("novel 插件执行设置与线程池")
class NovelExecutionConfigurationTest {

    @Test
    @DisplayName("设置使用插件默认并发并对手写过小值兜底")
    void settingsUseDefaultsAndClampUnsafeValues() {
        NovelExecutionSettings settings = new NovelExecutionSettings();

        assertThat(settings.getNovelMaxConcurrent()).isEqualTo(10);
        assertThat(settings.getNovelTranslateMaxConcurrent()).isEqualTo(10);
        settings.setNovelMaxConcurrent(0);
        settings.setNovelTranslateMaxConcurrent(-3);
        assertThat(settings.getNovelMaxConcurrent()).isEqualTo(1);
        assertThat(settings.getNovelTranslateMaxConcurrent()).isEqualTo(1);
    }

    @Test
    @DisplayName("子上下文绑定插件属性并在关闭时销毁线程池与状态调度器")
    void childContextOwnsAndDestroysConfiguredExecutors() {
        PluginApplicationContextFactory factory = new PluginApplicationContextFactory(owner -> Map.of(
                NovelExecutionSettings.DOWNLOAD_CONCURRENCY_KEY, "3",
                NovelExecutionSettings.TRANSLATION_CONCURRENCY_KEY, "4"),
                new PluginStreamRegistry(),
                new PluginRuntimeTaskRegistry());
        try (AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext()) {
            parent.refresh();
            ConfigurableApplicationContext child = factory.create(parent, new PluginContextModule(
                    "novel", getClass().getClassLoader(), List.of(NovelExecutionConfiguration.class)));
            ThreadPoolTaskExecutor download = child.getBean(
                    "novelDownloadTaskExecutor", ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor translate = child.getBean(
                    "novelTranslateTaskExecutor", ThreadPoolTaskExecutor.class);
            ThreadPoolTaskScheduler statusScheduler = child.getBean(
                    "novelStatusTaskScheduler", ThreadPoolTaskScheduler.class);
            try {
                NovelExecutionSettings settings = child.getBean(NovelExecutionSettings.class);
                assertThat(settings.getNovelMaxConcurrent()).isEqualTo(3);
                assertThat(settings.getNovelTranslateMaxConcurrent()).isEqualTo(4);
                assertThat(download.getCorePoolSize()).isEqualTo(3);
                assertThat(download.getMaxPoolSize()).isEqualTo(3);
                assertThat(download.getThreadNamePrefix()).isEqualTo("pixiv-novel-");
                assertThat(translate.getCorePoolSize()).isEqualTo(4);
                assertThat(translate.getMaxPoolSize()).isEqualTo(4);
                assertThat(translate.getThreadNamePrefix()).isEqualTo("pixiv-novel-tr-");
                assertThat(statusScheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                        .isEqualTo(1);
                assertThat(statusScheduler.getThreadNamePrefix())
                        .isEqualTo("novel-status-scheduler-");
                assertThat(statusScheduler.getScheduledThreadPoolExecutor()
                        .getRemoveOnCancelPolicy()).isTrue();
                assertThat(statusScheduler.getScheduledThreadPoolExecutor()
                        .getContinueExistingPeriodicTasksAfterShutdownPolicy()).isFalse();
                assertThat(statusScheduler.getScheduledThreadPoolExecutor()
                        .getExecuteExistingDelayedTasksAfterShutdownPolicy()).isFalse();
                assertThat(parent.containsBean("novelDownloadTaskExecutor")).isFalse();
                assertThat(parent.containsBean("novelTranslateTaskExecutor")).isFalse();
                assertThat(parent.containsBean("novelStatusTaskScheduler")).isFalse();
                assertThat(download.getThreadPoolExecutor().isShutdown()).isFalse();
                assertThat(translate.getThreadPoolExecutor().isShutdown()).isFalse();
                assertThat(statusScheduler.getScheduledThreadPoolExecutor().isShutdown())
                        .isFalse();
            } finally {
                child.close();
            }
            assertThat(download.getThreadPoolExecutor().isShutdown()).isTrue();
            assertThat(translate.getThreadPoolExecutor().isShutdown()).isTrue();
            assertThat(statusScheduler.getScheduledThreadPoolExecutor().isShutdown()).isTrue();
        }
    }

    @Test
    @DisplayName("禁用 novel 时子上下文不创建执行设置和线程池")
    void disabledPluginHasNoExecutionBeans() {
        PluginApplicationContextFactory factory = new PluginApplicationContextFactory(owner -> Map.of(
                "plugins.novel.enabled", "false"),
                new PluginStreamRegistry(),
                new PluginRuntimeTaskRegistry());
        try (AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext()) {
            parent.refresh();
            try (ConfigurableApplicationContext child = factory.create(parent, new PluginContextModule(
                    "novel", getClass().getClassLoader(), List.of(NovelExecutionConfiguration.class)))) {
                assertThat(child.getBeansOfType(NovelExecutionSettings.class)).isEmpty();
                assertThat(child.containsLocalBean("novelDownloadTaskExecutor")).isFalse();
                assertThat(child.containsLocalBean("novelTranslateTaskExecutor")).isFalse();
                assertThat(child.containsLocalBean("novelStatusTaskScheduler")).isFalse();
            }
        }
    }
}
