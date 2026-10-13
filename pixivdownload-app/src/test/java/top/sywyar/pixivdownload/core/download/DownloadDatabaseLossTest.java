package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.*;
import top.sywyar.pixivdownload.plugin.api.download.submission.*;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("数据库在外部回调内失效时停止后续派发")
class DownloadDatabaseLossTest {
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final DownloadLifecycleRegistry registry = new DownloadLifecycleRegistry(new DownloadTaskRegistry(), available::get);
    private final ExternalCapabilityOwner owner = new ExternalCapabilityOwner("fixture", "fixture", 1, 1);
    private final DownloadAttempt attempt = new DownloadAttempt(UUID.randomUUID(), "fixture", "1");

    @Test @DisplayName("最后一条准入规则返回允许时已经失库，不调用下载处理器且返回不可用")
    void policyCannotAdmitAfterDatabaseLoss() {
        var handler = mock(DownloadSubmissionHandler.class);
        registry.register(owner, new DownloadLifecycleRegistry.Contribution(List.of(), List.of(value -> {
            available.set(false); return DownloadAdmissionPolicy.Decision.ALLOW;
        }), List.of(), Map.of("fixture", handler)));
        assertThatThrownBy(() -> registry.submit(new DownloadSubmission(UUID.randomUUID(), "fixture", "1", Map.of()),
                "", RequestOwnerIdentity.adminScope())).isInstanceOfSatisfying(DownloadTaskException.class,
                        failure -> assertThat(failure.code()).isEqualTo(DownloadTaskException.Code.UNAVAILABLE));
        verifyNoInteractions(handler);
        assertThat(registry.snapshot(RequestOwnerIdentity.adminScope()).tasks()).allSatisfy(task ->
                assertThat(task.phase()).isEqualTo(DownloadEvent.Phase.FAILED));
    }

    @Test @DisplayName("首个选项回调后失库，后续回调不执行且不可用异常不降为一般拒绝")
    void optionsStopAfterLoss() {
        var later = new AtomicInteger();
        registry.register(owner, new DownloadLifecycleRegistry.Contribution(List.of(), List.of(), List.of(
                new DownloadLifecycleRegistry.Hook(0, "first", (a, value) -> { available.set(false); return value; }),
                new DownloadLifecycleRegistry.Hook(1, "later", (a, value) -> { later.incrementAndGet(); return value; })), Map.of()));
        assertThatThrownBy(() -> registry.options(attempt, Map.of())).isInstanceOf(DatabaseUnavailableException.class);
        assertThat(later).hasValue(0);
    }

    @Test @DisplayName("观察回调中失库后停止通知，失败终态仅完成内部收敛")
    void observersStopWithoutLosingDrain() {
        var later = new AtomicInteger();
        registry.register(owner, new DownloadLifecycleRegistry.Contribution(List.of(
                event -> available.set(false), event -> later.incrementAndGet()), List.of()));
        registry.publish(new DownloadEvent(attempt, DownloadEvent.Phase.ACCEPTED));
        registry.publish(new DownloadEvent(attempt, DownloadEvent.Phase.FAILED));
        assertThat(later).hasValue(0);
        assertThat(registry.observerFailureCount()).isZero();
    }
}
