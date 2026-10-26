package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("桌面测试等待已发布快照")
class DesktopSnapshotAwaiterTest {
    @Test
    @DisplayName("忙碌标记先清除时仍等待可交互快照发布")
    void waitsForPublishedReadySnapshot() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>())) {
            var started = new CountDownLatch(1);
            java.util.concurrent.Future<DesktopUiSnapshot> waiting;
            synchronized (model) {
                model.setBusy(true);
                model.rebuild();
                model.setBusy(false);
                waiting = executor.submit(() -> {
                    started.countDown();
                    DesktopConfigurationControllerTest.awaitReady(model);
                    return model.snapshot();
                });
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));
                } finally {
                    model.rebuild();
                }
            }
            assertTrue(DesktopUiEventProtocol.index(waiting.get(5, TimeUnit.SECONDS).document())
                    .get("config.reload").enabled());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("已有快照立即满足等待，完成和断言失败均撤销订阅")
    void currentSnapshotAndPredicateFailureReleaseSubscription() throws Exception {
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>())) {
            var calls = new AtomicInteger();
            assertSame(model.snapshot(), DesktopSnapshotAwaiter.await(model, snapshot -> {
                calls.incrementAndGet();
                return true;
            }));
            var expected = new AssertionError("controlled predicate failure");
            var failure = assertThrows(AssertionError.class, () -> DesktopSnapshotAwaiter.await(model, snapshot -> {
                calls.incrementAndGet();
                throw expected;
            }));
            assertSame(expected, failure.getCause().getCause());
            model.setBusy(true);
            model.rebuild();
            assertEquals(2, calls.get());
        }
    }
}
