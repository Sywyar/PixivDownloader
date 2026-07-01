package top.sywyar.pixivdownload.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("通知协调器：场景级开关裁剪")
class NotificationServiceTest {

    /** 记录被下发场景 id 的假介质（替代真实邮件 / 推送）。 */
    private static final class RecordingSink implements NotificationSink {
        final List<String> delivered = new ArrayList<>();

        @Override
        public String medium() {
            return "recording";
        }

        @Override
        public void deliver(NotificationScenario scenario, Locale locale, Map<String, String> placeholders) {
            delivered.add(scenario.id());
        }

        @Override
        public void verifyRenderable(NotificationScenario scenario) {
            // 测试桩：始终可渲染
        }
    }

    @Test
    @DisplayName("默认全部启用：场景会扇出到所有介质")
    void enabledByDefaultDeliversToAllSinks() {
        RecordingSink sink = new RecordingSink();
        NotificationService service = new NotificationService(new NotificationSinkRegistry(List.of(sink)),
                new NotificationConfig());

        service.notify(NotificationScenario.RUN_SUMMARY, Locale.SIMPLIFIED_CHINESE, Map.of());

        assertThat(sink.delivered).containsExactly(NotificationScenario.RUN_SUMMARY.id());
    }

    @Test
    @DisplayName("关闭某场景后：该场景跳过全部介质，其它场景不受影响")
    void disabledScenarioSkipsAllSinks() {
        RecordingSink sink = new RecordingSink();
        NotificationConfig config = new NotificationConfig();
        config.setScenarioEnabled(NotificationScenario.RUN_SUMMARY.id(), false);
        NotificationService service = new NotificationService(new NotificationSinkRegistry(List.of(sink)), config);

        service.notify(NotificationScenario.RUN_SUMMARY, Locale.SIMPLIFIED_CHINESE, Map.of());
        service.notify(NotificationScenario.RUN_FAILED, Locale.SIMPLIFIED_CHINESE, Map.of());

        assertThat(sink.delivered).containsExactly(NotificationScenario.RUN_FAILED.id());
    }
}
