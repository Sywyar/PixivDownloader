package top.sywyar.pixivdownload.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("通知类型开关配置：绑定与默认")
class NotificationConfigTest {

    @Test
    @DisplayName("未配置的场景默认视为启用")
    void unconfiguredScenarioDefaultsEnabled() {
        NotificationConfig config = new NotificationConfig();
        for (NotificationScenario scenario : NotificationScenario.values()) {
            assertThat(config.isScenarioEnabled(scenario.id()))
                    .as("场景 [%s] 默认应启用", scenario.id())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("kebab-case 场景 id 的 .enabled 键能正确绑定到 Map，未列出的场景仍默认启用")
    void bindsKebabCaseScenarioKeys() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(NotificationConfig.scenarioEnabledKey("run-summary"), "false");
        props.put(NotificationConfig.scenarioEnabledKey("overuse-paused"), "true");

        Binder binder = new Binder(new MapConfigurationPropertySource(props));
        NotificationConfig config = binder.bind("notification", Bindable.of(NotificationConfig.class))
                .orElseGet(NotificationConfig::new);

        assertThat(config.isScenarioEnabled("run-summary")).isFalse();
        assertThat(config.isScenarioEnabled("overuse-paused")).isTrue();
        assertThat(config.isScenarioEnabled("auth-expired")).isTrue();
    }
}
