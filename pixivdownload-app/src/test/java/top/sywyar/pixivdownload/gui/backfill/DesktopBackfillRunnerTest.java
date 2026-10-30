package top.sywyar.pixivdownload.gui.backfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.*;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("后台回填任务状态与生命周期")
class DesktopBackfillRunnerTest {
    @ParameterizedTest
    @EnumSource(value = BackfillState.class, names = {"COMPLETED", "INCOMPLETE", "RATE_LIMITED", "FAILED"})
    @DisplayName("按真实结果区分终态，日志资源关闭后才发布结束")
    void publishesActualOutcomeAfterCleanup(BackfillState expected) throws Exception {
        var closed = new AtomicBoolean();
        var terminal = new AtomicReference<BackfillTask>();
        var finished = new CountDownLatch(1);
        var summary = new ArtworksBackFill.Summary(1, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                false, expected == BackfillState.RATE_LIMITED, 1);
        var runner = new DesktopBackfillRunner((options, progress) -> {
            progress.accept(new ArtworksBackFill.Progress(ArtworksBackFill.Phase.LOCAL, 1, 1, 1,
                    expected == BackfillState.INCOMPLETE ? 1 : 0, 0, null, null, summary));
            if (expected == BackfillState.FAILED) throw new java.io.IOException("fixture");
            return summary;
        }, () -> log(closed));
        runner.start();
        try {
            var receipt = runner.start(options(), value -> {
                if (!value.active()) {
                    assertTrue(closed.get());
                    terminal.set(value);
                    finished.countDown();
                }
            });
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            var result = terminal.get();
            assertEquals(receipt.executionId(), result.executionId());
            assertEquals(expected, result.state());
            assertEquals(1, result.changed());
            assertEquals(1, result.processed());
            assertEquals(1, result.summary().filledUploadTime());
            assertEquals("backfill-fixture.html", result.logFileName());
            assertTrue(result.finishedAtEpochMs() >= result.startedAtEpochMs());
        } finally { runner.stop(); }
    }

    @Test
    @DisplayName("停止先显示清理中，拒绝重复执行与旧身份，后端等待物理工作退出")
    void cancellationWaitsForPhysicalExit() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var terminal = new CountDownLatch(1);
        var latest = new AtomicReference<BackfillTask>();
        var closed = new AtomicBoolean();
        var runner = new DesktopBackfillRunner((options, progress) -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException cancelled) {
                interrupted.countDown();
                release.await();
                throw cancelled;
            }
            throw new AssertionError();
        }, () -> log(closed));
        runner.start();
        Thread stopper = null;
        try {
            var receipt = runner.start(options(), value -> {
                latest.accumulateAndGet(value, (old, next) -> old == null || next.revision() > old.revision() ? next : old);
                if (!value.active()) terminal.countDown();
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(runner.cancel(UUID.randomUUID()));
            assertThrows(IllegalStateException.class, () -> runner.start(options(), value -> {}));
            assertTrue(runner.cancel(receipt.executionId()));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertEquals(BackfillState.STOPPING, latest.get().state());
            assertEquals(1, terminal.getCount());
            assertFalse(closed.get());
            stopper = new Thread(runner::stop);
            stopper.start();
            assertTrue(stopper.isAlive());
            release.countDown();
            stopper.join(5000);
            assertFalse(stopper.isAlive());
            assertTrue(terminal.await(5, TimeUnit.SECONDS));
            assertEquals(BackfillState.CANCELLED, latest.get().state());
            assertTrue(closed.get());
            assertFalse(runner.isRunning());
            assertThrows(IllegalStateException.class, () -> runner.start(options(), value -> {}));
        } finally {
            release.countDown();
            runner.stop();
            if (stopper != null) stopper.join(5000);
        }
    }

    @Test
    @DisplayName("辅助日志无法创建时继续执行，不打开浏览器")
    void unavailableLogDoesNotBlockBackfill() throws Exception {
        var terminal = new AtomicReference<BackfillTask>();
        var done = new CountDownLatch(1);
        var runner = new DesktopBackfillRunner((options, progress) ->
                new ArtworksBackFill.Summary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false, 0),
                () -> { throw new java.io.IOException("fixture"); });
        runner.start();
        try {
            runner.start(options(), value -> { if (!value.active()) { terminal.set(value); done.countDown(); } });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(BackfillState.COMPLETED, terminal.get().state());
            assertNull(terminal.get().logFileName());
        } finally { runner.stop(); }
    }

    private static ArtworksBackFill.Options options() {
        return new ArtworksBackFill.Options("fixture.db", "", 0, false, 0, 1, false);
    }

    private static ToolLogSession log(AtomicBoolean closed) {
        return new ToolLogSession() {
            public Path latestPath() { return Path.of("backfill-latest.html"); }
            public Path sessionPath() { return Path.of("backfill-fixture.html"); }
            public void openLatestInBrowser() { fail("Backfill must not open a browser"); }
            public void close() { closed.set(true); }
        };
    }
}
