package top.sywyar.pixivdownload.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("通知场景配置键")
class NotificationConfigKeysTest {

    @Test
    @DisplayName("场景标识生成稳定配置键")
    void exposesScenarioKeysWithoutPluginOwnerIdentity() {
        assertThat(NotificationConfigKeys.scenarioEnabledKey("run-summary"))
                .isEqualTo("notification.scenario.run-summary.enabled");
        assertThat(NotificationConfigKeys.SCENARIO_PREFIX).isEqualTo("notification.scenario.");
        assertThat(NotificationConfigKeys.SCENARIO_ENABLED_SUFFIX).isEqualTo(".enabled");
    }
}
