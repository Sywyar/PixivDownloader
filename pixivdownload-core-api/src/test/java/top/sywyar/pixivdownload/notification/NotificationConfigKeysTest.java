package top.sywyar.pixivdownload.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("通知场景配置键")
class NotificationConfigKeysTest {

    @Test
    @DisplayName("只表达中性场景键而不暴露具体插件 owner")
    void exposesScenarioKeysWithoutPluginOwnerIdentity() {
        assertThat(NotificationConfigKeys.scenarioEnabledKey("run-summary"))
                .isEqualTo("notification.scenario.run-summary.enabled");
        var publicFields = Arrays.stream(NotificationConfigKeys.class.getDeclaredFields())
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .toList();

        assertThat(publicFields)
                .extracting(Field::getName)
                .containsExactlyInAnyOrder("SCENARIO_PREFIX", "SCENARIO_ENABLED_SUFFIX");
        assertThat(publicFields).allSatisfy(field -> {
            assertThat(field.getType()).isEqualTo(String.class);
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        });
        assertThat(NotificationConfigKeys.SCENARIO_PREFIX).isEqualTo("notification.scenario.");
        assertThat(NotificationConfigKeys.SCENARIO_ENABLED_SUFFIX).isEqualTo(".enabled");
    }
}
