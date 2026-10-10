package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import top.sywyar.pixivdownload.download.media.UgoiraEncoderSettings;
import top.sywyar.pixivdownload.download.schedule.PixivScheduleSettings;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.plugin.runtime.stream.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry;
import top.sywyar.pixivdownload.schedule.ScheduleConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("下载工作台真实插件上下文配置绑定")
class DownloadWorkbenchConfigurationBindingTest {
    @Test
    @DisplayName("启动读取动图与计划设置，重新创建上下文读取更新值")
    void bindsSavedSettingsOnStartupAndRestart() {
        Map<String, Object> values = new HashMap<>(Map.of(
                "download-workbench.ugoira.parallelism", "1",
                "download-workbench.ugoira.lossless-effort", "12",
                "download-workbench.ugoira.max-output-mib", "16",
                "download-workbench.ugoira.temporary-budget-gib", "1",
                "download-workbench.ugoira.timeout-minutes", "1",
                "schedule.enabled", "false",
                "schedule.max-tasks", "7",
                "schedule.inbox-check-every", "13",
                "schedule.overuse-defer-default-minutes", "17"));
        values.put("schedule.tick-interval-ms", "120000");
        values.put("schedule.auth-failure-circuit-breaker", "3");
        values.put("schedule.pending-max-attempts", "2");
        try (var parent = parent(values)) {
            for (int parallelism : new int[]{1, 6}) {
                values.put("download-workbench.ugoira.parallelism", Integer.toString(parallelism));
                try (var child = child(parent)) {
                    var encoder = child.getBean(UgoiraEncoderSettings.class);
                    var schedule = child.getBean(ScheduleConfig.class);
                    var pixiv = child.getBean(PixivScheduleSettings.class);
                    assertAll(
                            () -> assertThat(encoder.getParallelism()).isEqualTo(parallelism),
                            () -> assertThat(encoder.getLosslessEffort()).isEqualTo(12),
                            () -> assertThat(encoder.getMaxOutputMib()).isEqualTo(16),
                            () -> assertThat(encoder.getTemporaryBudgetGib()).isEqualTo(1),
                            () -> assertThat(encoder.getTimeoutMinutes()).isEqualTo(1),
                            () -> assertThat(schedule.isEnabled()).isFalse(),
                            () -> assertThat(schedule.getTickIntervalMs()).isEqualTo(120000),
                            () -> assertThat(schedule.getMaxTasks()).isEqualTo(7),
                            () -> assertThat(schedule.getAuthFailureCircuitBreaker()).isEqualTo(3),
                            () -> assertThat(schedule.getPendingMaxAttempts()).isEqualTo(2),
                            () -> assertThat(pixiv.getInboxCheckEvery()).isEqualTo(13),
                            () -> assertThat(pixiv.getOveruseDeferDefaultMinutes()).isEqualTo(17));
                }
            }
        }
    }

    @Test
    @DisplayName("没有保存配置时沿用各配置对象的默认值")
    void missingSettingsKeepDefaults() {
        try (var parent = parent(Map.of()); var child = child(parent)) {
            assertThat(child.getBean(UgoiraEncoderSettings.class))
                    .usingRecursiveComparison().isEqualTo(new UgoiraEncoderSettings());
            assertThat(child.getBean(ScheduleConfig.class))
                    .usingRecursiveComparison().isEqualTo(new ScheduleConfig());
            assertThat(child.getBean(PixivScheduleSettings.class))
                    .usingRecursiveComparison().isEqualTo(new PixivScheduleSettings());
        }
    }

    @ParameterizedTest
    @CsvSource({"parallelism,0", "lossless-effort,101", "max-output-mib,0",
            "temporary-budget-gib,0", "timeout-minutes,0", "parallelism,1.5", "parallelism,text"})
    @DisplayName("插件装配拒绝非法动图资源值，不静默回退默认值")
    void rejectsInvalidEncoderSettings(String key, String value) {
        try (var parent = parent(Map.of("download-workbench.ugoira." + key, value));
             var child = child(parent)) {
            assertThatThrownBy(() -> child.getBean(UgoiraEncoderSettings.class))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("无损计算量的零值是合法配置")
    void acceptsZeroLosslessEffort() {
        try (var parent = parent(Map.of("download-workbench.ugoira.lossless-effort", "0"));
             var child = child(parent)) {
            assertThat(child.getBean(UgoiraEncoderSettings.class).getLosslessEffort()).isZero();
        }
    }

    private static AnnotationConfigApplicationContext parent(Map<String, Object> values) {
        var parent = new AnnotationConfigApplicationContext();
        parent.getEnvironment().getPropertySources().addFirst(new MapPropertySource("saved-plugin-settings", values));
        parent.register(ParentConfiguration.class);
        parent.refresh();
        return parent;
    }

    private static ConfigurableApplicationContext child(AnnotationConfigApplicationContext parent) {
        var configurations = new ArrayList<Class<?>>(new DownloadWorkbenchPf4jPlugin().configurationClasses());
        configurations.add(LazyBusinessBeans.class);
        var factory = new PluginApplicationContextFactory(new PluginStreamRegistry(), new PluginRuntimeTaskRegistry());
        return factory.create(parent, new PluginContextModule(DownloadWorkbenchPlugin.ID,
                DownloadWorkbenchConfigurationBindingTest.class.getClassLoader(), configurations));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    static class ParentConfiguration {
        @Bean
        ThreadPoolTaskExecutorBuilder taskExecutorBuilder() {
            return new ThreadPoolTaskExecutorBuilder();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LazyBusinessBeans {
        @Bean
        static BeanFactoryPostProcessor deferBusinessBeans() {
            // 保留真实插件装配和绑定基础设施，只延迟无关下载、数据库与网络服务。
            return factory -> {
                for (String name : factory.getBeanDefinitionNames()) {
                    factory.getBeanDefinition(name).setLazyInit(true);
                }
            };
        }
    }
}
