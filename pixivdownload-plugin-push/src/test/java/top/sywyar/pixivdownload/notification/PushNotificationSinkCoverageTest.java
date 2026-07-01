package top.sywyar.pixivdownload.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;
import top.sywyar.pixivdownload.push.PushConfig;
import top.sywyar.pixivdownload.push.PushDispatcher;
import top.sywyar.pixivdownload.push.PushMessage;
import top.sywyar.pixivdownload.push.PushMessageFactory;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.TestMessageResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("推送通知介质场景覆盖")
class PushNotificationSinkCoverageTest {

    private final PushNotificationSink sink = new PushNotificationSink(
            new PushConfig(),
            new PushMessageFactory(TestMessageResolver.INSTANCE),
            new NoopPushDispatcher());

    @Test
    @DisplayName("每个通知场景都有中英文可渲染推送文案")
    void everyScenarioRenderable() {
        assertThat(NotificationScenario.values()).isNotEmpty();
        for (NotificationScenario scenario : NotificationScenario.values()) {
            assertThatCode(() -> sink.verifyRenderable(scenario))
                    .as("push 缺少场景 [%s] 的渲染资源", scenario.id())
                    .doesNotThrowAnyException();
        }
    }

    private static final class NoopPushDispatcher implements PushDispatcher {
        @Override
        public List<PushResult> push(PushMessage message) {
            return List.of();
        }

        @Override
        public PushResult push(PushChannelType type, PushMessage message) {
            return PushResult.skipped(type, "test noop");
        }

        @Override
        public List<PushResult> test(List<PushChannelSettings> settings, PushMessage message) {
            return List.of();
        }
    }
}
