package top.sywyar.pixivdownload.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.binder.logging.LogbackMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.metrics.LogbackMetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.ResourcePropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("后端指标注册表生命周期")
class MetricsLifecycleConfigurationTest {

    @Test
    @DisplayName("后端反复启停保留当前指标功能且不向进程全局注册表累积实例")
    void backendRestartsShouldKeepMetricsScopedToEachContext() throws Exception {
        var properties = new ResourcePropertySource("classpath:application.properties");
        var runner = new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addLast(properties))
                .withUserConfiguration(MetricsConfiguration.class)
                .withConfiguration(AutoConfigurations.of(
                        MetricsAutoConfiguration.class, SimpleMetricsExportAutoConfiguration.class,
                        LogbackMetricsAutoConfiguration.class));
        Set<MeterRegistry> globalRegistries = Set.copyOf(Metrics.globalRegistry.getRegistries());
        List<MeterRegistry> createdRegistries = new ArrayList<>();
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        List<LoggerContextListener> originalListeners = loggerContext.getCopyOfListenerList();

        try {
            for (int restart = 0; restart < 3; restart++) {
                runner.run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MeterRegistry.class);
                    assertThat(context).hasSingleBean(LogbackMetrics.class);
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    createdRegistries.add(registry);
                    registry.counter("backend.lifecycle.test").increment();
                    assertThat(registry.get("backend.lifecycle.test").counter().count()).isEqualTo(1.0);
                    assertThat(registry.find("logback.events").tag("level", "warn").counter()).isNotNull();
                    assertThat(Metrics.globalRegistry.getRegistries()).doesNotContain(registry);
                });
                assertThat(createdRegistries.get(restart).isClosed()).isTrue();
                assertThat(loggerContext.getCopyOfListenerList()).containsExactlyElementsOf(originalListeners);
            }
            assertThat(Metrics.globalRegistry.getRegistries())
                    .containsExactlyInAnyOrderElementsOf(globalRegistries);
        } finally {
            createdRegistries.forEach(Metrics::removeRegistry);
        }
    }

    @Test
    @DisplayName("日志指标支持重置且关闭后撤回自身监听器和过滤器")
    void closedLogbackMetricsShouldNotReturnAfterLoggingReset() {
        LoggerContext loggerContext = new LoggerContext();
        LoggerContextListener unrelated = mock(LoggerContextListener.class);
        when(unrelated.isResetResistant()).thenReturn(true);
        loggerContext.addListener(unrelated);
        try {
            for (int restart = 0; restart < 3; restart++) {
                var registry = new SimpleMeterRegistry();
                try (var metrics = new MetricsConfiguration.ContextLogbackMetrics(loggerContext)) {
                    metrics.bindTo(registry);
                    var logger = loggerContext.getLogger("metrics-lifecycle-test");
                    var warnings = registry.get("logback.events").tag("level", "warn").counter();
                    logger.warn("before reset");
                    assertThat(warnings.count()).isEqualTo(1.0);
                    loggerContext.reset();
                    logger.warn("after reset");
                    assertThat(warnings.count()).isEqualTo(2.0);
                    assertThat(loggerContext.getTurboFilterList()).hasSize(1);

                    LoggerContextListener pendingReset = loggerContext.getCopyOfListenerList().stream()
                            .filter(listener -> listener != unrelated).findFirst().orElseThrow();
                    metrics.close();
                    assertThat(loggerContext.getCopyOfListenerList()).containsExactly(unrelated);
                    assertThat(loggerContext.getTurboFilterList()).isEmpty();
                    pendingReset.onReset(loggerContext);
                    loggerContext.reset();
                    assertThat(loggerContext.getTurboFilterList()).isEmpty();
                    logger.warn("after close");
                    assertThat(warnings.count()).isEqualTo(2.0);
                } finally {
                    registry.close();
                }
            }
        } finally {
            loggerContext.stop();
        }
    }
}
