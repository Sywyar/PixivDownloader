package top.sywyar.pixivdownload.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("宿主计划配置绑定")
class ScheduleConfigBindingTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(application -> application.getBeanFactory().registerSingleton("scheduleConfig",
                    new ScheduleHostConfiguration().scheduleConfig(application.getEnvironment())));

    @Test
    @DisplayName("无配置时保留默认值，重建宿主读取已保存的非默认值")
    void bindsSavedSettingsAndDefaults() {
        context.run(application -> assertThat(application.getBean(ScheduleConfig.class))
                .usingRecursiveComparison().isEqualTo(new ScheduleConfig()));
        for (boolean enabled : new boolean[]{false, true}) {
            context.withPropertyValues("schedule.enabled=" + enabled,
                    "schedule.tick-interval-ms=120000", "schedule.max-tasks=7",
                    "schedule.auth-failure-circuit-breaker=3", "schedule.pending-max-attempts=2")
                    .run(application -> {
                        ScheduleConfig config = application.getBean(ScheduleConfig.class);
                        assertThat(config.isEnabled()).isEqualTo(enabled);
                        assertThat(config.getTickIntervalMs()).isEqualTo(120000);
                        assertThat(config.getMaxTasks()).isEqualTo(7);
                        assertThat(config.getAuthFailureCircuitBreaker()).isEqualTo(3);
                        assertThat(config.getPendingMaxAttempts()).isEqualTo(2);
                    });
        }
    }
}
