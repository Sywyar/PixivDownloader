package top.sywyar.pixivdownload.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("中性通知场景")
class NotificationScenarioTest {

    @Test
    @DisplayName("凭证与策略场景改用中性枚举名且保持已发布标识")
    void neutralCredentialAndPolicyNamesKeepPublishedScenarioIds() {
        assertThat(NotificationScenario.POLICY_ACCOUNT_SUSPENDED.id())
                .isEqualTo("overuse-paused");
        assertThat(NotificationScenario.CREDENTIAL_SUSPENDED.id())
                .isEqualTo("auth-expired");
        assertThat(NotificationScenario.CREDENTIAL_FAILURE_CIRCUIT_OPEN.id())
                .isEqualTo("circuit-breaker");
        assertThat(NotificationScenario.CREDENTIAL_REVOKED_CONTINUING.id())
                .isEqualTo("degraded-anonymous");
    }

    @Test
    @DisplayName("场景只按已发布标识解析且拒绝未知插件原因码")
    void canonicalScenarioLookupRejectsUnknownIds() {
        assertThat(NotificationScenario.findById(" overuse-paused "))
                .contains(NotificationScenario.POLICY_ACCOUNT_SUSPENDED);
        assertThat(NotificationScenario.findById("plugin-private-reason")).isEmpty();
        assertThat(NotificationScenario.findById(null)).isEmpty();
    }

    @Test
    @DisplayName("维护失败场景使用系统分类")
    void maintenanceFailureUsesSystemCategory() {
        assertThat(NotificationScenario.MAINTENANCE_TASK_FAILED.categoryId()).isEqualTo("system");
        assertThat(NotificationScenario.RUN_SUMMARY.categoryId()).isEqualTo("download");
    }
}
