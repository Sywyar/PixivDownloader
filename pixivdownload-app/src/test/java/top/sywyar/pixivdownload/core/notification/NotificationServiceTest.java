package top.sywyar.pixivdownload.core.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.notification.NotificationSink;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityUnavailableException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("通知协调器：场景级开关裁剪")
class NotificationServiceTest {

    @Test
    @DisplayName("状态发布失败时通知 owner 与可见快照保持原子一致")
    void registryStatePublicationFailureKeepsOwnerAndSnapshotAtomic() {
        AtomicReference<Throwable> nextFailure = new AtomicReference<>();
        NotificationSinkRegistry registry = new NotificationSinkRegistry(
                List.of(), () -> throwPending(nextFailure));
        RecordingSink first = new RecordingSink();
        RecordingSink second = new RecordingSink();
        registry.registerPrepared("owner-a", 1L, List.of(
                new NotificationSinkRegistry.PreparedSink("first", first, "first.Type")));
        List<NotificationSink> beforePublish = registry.sinks();

        for (Throwable expected : failures("publish")) {
            nextFailure.set(expected);
            assertThat(catchThrowable(() -> registry.registerPrepared("owner-b", 2L, List.of(
                    new NotificationSinkRegistry.PreparedSink("second", second, "second.Type")))))
                    .isSameAs(expected);
            assertThat(registry.sinks()).isSameAs(beforePublish);
        }

        registry.registerPrepared("owner-b", 2L, List.of(
                new NotificationSinkRegistry.PreparedSink("second", second, "second.Type")));
        List<NotificationSink> beforeWithdraw = registry.sinks();
        for (Throwable expected : failures("withdraw")) {
            nextFailure.set(expected);
            assertThat(catchThrowable(() -> registry.unregisterPrepared("owner-b", 2L))).isSameAs(expected);
            assertThat(registry.sinks()).isSameAs(beforeWithdraw);
        }
        registry.unregisterPrepared("owner-b", 2L);
        assertThat(registry.sinks()).containsExactly(first);
    }

    /** 记录被下发场景 id 的假介质（替代真实邮件 / 推送）。 */
    private static final class RecordingSink implements NotificationSink {
        final List<String> delivered = new ArrayList<>();
        RuntimeException deliveryFailure;
        boolean failMediumLookup;

        @Override
        public String medium() {
            if (failMediumLookup) {
                throw new AssertionError("service must use captured notification medium");
            }
            return "recording";
        }

        @Override
        public void deliver(NotificationScenario scenario, Locale locale, Map<String, String> placeholders) {
            if (deliveryFailure != null) {
                throw deliveryFailure;
            }
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

    @Test
    @DisplayName("介质撤回后的迟到调用使用注册快照记录诊断且不向业务逃逸")
    void withdrawnSinkFailsSoftWithoutMetadataCallback() {
        RecordingSink sink = new RecordingSink();
        NotificationService service = new NotificationService(
                new NotificationSinkRegistry(List.of(sink)), new NotificationConfig());
        sink.deliveryFailure = new ExternalCapabilityUnavailableException("withdrawn");
        sink.failMediumLookup = true;

        service.notify(NotificationScenario.RUN_SUMMARY, Locale.SIMPLIFIED_CHINESE, Map.of());

        assertThat(sink.delivered).isEmpty();
    }

    private static List<Throwable> failures(String action) {
        return List.of(
                new IllegalStateException("ordinary-" + action),
                new OutOfMemoryError("fatal-" + action),
                new ThreadDeath());
    }

    private static void throwPending(AtomicReference<Throwable> pending) {
        Throwable failure = pending.getAndSet(null);
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
