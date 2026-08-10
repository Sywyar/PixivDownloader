package top.sywyar.pixivdownload.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.push.PushChannelId;
import top.sywyar.pixivdownload.push.PushChannelIds;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushConfig;
import top.sywyar.pixivdownload.push.PushDispatcher;
import top.sywyar.pixivdownload.push.PushMessage;
import top.sywyar.pixivdownload.push.PushMessageFactory;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.TestMessageResolver;
import top.sywyar.pixivdownload.push.TestNotificationTemplates;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("推送通知介质场景覆盖")
class PushNotificationSinkCoverageTest {

    private final PushNotificationSink sink = new PushNotificationSink(
            new PushConfig(),
            new PushMessageFactory(TestNotificationTemplates.catalog()),
            new NoopPushDispatcher(),
            TestMessageResolver.INSTANCE);

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

    @Test
    @DisplayName("通知严重程度直接透传给推送通道映射颜色与优先级")
    void notificationSeverityPassesThroughOnDelivery() {
        PushConfig config = new PushConfig();
        config.setEnabled(true);
        CapturingPushDispatcher dispatcher = new CapturingPushDispatcher();
        PushNotificationSink enabledSink = new PushNotificationSink(
                config,
                new PushMessageFactory(TestNotificationTemplates.catalog()),
                dispatcher,
                TestMessageResolver.INSTANCE);

        enabledSink.deliver(
                NotificationScenario.CREDENTIAL_FAILURE_CIRCUIT_OPEN,
                Locale.SIMPLIFIED_CHINESE,
                Map.of());

        assertThat(dispatcher.message).isNotNull();
        assertThat(dispatcher.message.level()).isEqualTo(NotificationSeverity.ERROR);
    }

    @Test
    @DisplayName("派发失败结果与意外异常均在通知介质边界收敛")
    void deliveryFailuresRemainBestEffort() {
        PushConfig config = new PushConfig();
        config.setEnabled(true);

        for (PushDispatcher dispatcher : List.of(
                new FailedPushDispatcher(),
                new ThrowingPushDispatcher())) {
            PushNotificationSink failingSink = new PushNotificationSink(
                    config,
                    new PushMessageFactory(TestNotificationTemplates.catalog()),
                    dispatcher,
                    TestMessageResolver.INSTANCE);

            assertThatCode(() -> failingSink.deliver(
                    NotificationScenario.RUN_FAILED, Locale.US, Map.of()))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("推送文案解析异常不会穿透通知介质的故障安全边界")
    void messageResolutionFailuresRemainBestEffort() {
        PushConfig config = new PushConfig();
        config.setEnabled(true);
        PushNotificationSink deliveryLoggingSink = new PushNotificationSink(
                config,
                new PushMessageFactory(TestNotificationTemplates.catalog()),
                new FailedPushDispatcher(),
                TestMessageResolver.THROWING);
        PushNotificationSink renderLoggingSink = new PushNotificationSink(
                config,
                new PushMessageFactory(TestNotificationTemplates.throwing()),
                new NoopPushDispatcher(),
                TestMessageResolver.THROWING);

        assertThatCode(() -> deliveryLoggingSink.deliver(
                NotificationScenario.RUN_FAILED, Locale.US, Map.of()))
                .doesNotThrowAnyException();
        assertThatCode(() -> renderLoggingSink.deliver(
                NotificationScenario.RUN_FAILED, Locale.US, Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("空通知场景不会穿透介质的故障安全边界")
    void nullScenarioRemainsBestEffort() {
        PushConfig config = new PushConfig();
        config.setEnabled(true);
        PushNotificationSink enabledSink = new PushNotificationSink(
                config,
                new PushMessageFactory(TestNotificationTemplates.catalog()),
                new NoopPushDispatcher(),
                TestMessageResolver.INSTANCE);

        assertThatCode(() -> enabledSink.deliver(null, Locale.US, Map.of()))
                .doesNotThrowAnyException();
    }

    private static class NoopPushDispatcher implements PushDispatcher {
        @Override
        public List<PushResult> push(PushMessage message) {
            return List.of();
        }

        @Override
        public PushResult push(PushChannelId channelId, PushMessage message) {
            return PushResult.skipped(channelId, "test noop");
        }

        @Override
        public List<PushResult> test(List<PushChannelSettings> settings, PushMessage message) {
            return List.of();
        }
    }

    private static final class CapturingPushDispatcher implements PushDispatcher {
        private PushMessage message;

        @Override
        public List<PushResult> push(PushMessage message) {
            this.message = message;
            return List.of(PushResult.ok(PushChannelIds.BARK));
        }

        @Override
        public PushResult push(PushChannelId channelId, PushMessage message) {
            this.message = message;
            return PushResult.ok(channelId);
        }

        @Override
        public List<PushResult> test(List<PushChannelSettings> settings, PushMessage message) {
            this.message = message;
            return List.of(PushResult.ok(PushChannelIds.BARK));
        }
    }

    private static final class FailedPushDispatcher extends NoopPushDispatcher {
        @Override
        public List<PushResult> push(PushMessage message) {
            return List.of(PushResult.failed(PushChannelIds.BARK, "test failure"));
        }
    }

    private static final class ThrowingPushDispatcher extends NoopPushDispatcher {
        @Override
        public List<PushResult> push(PushMessage message) {
            throw new IllegalStateException("test failure");
        }
    }
}
