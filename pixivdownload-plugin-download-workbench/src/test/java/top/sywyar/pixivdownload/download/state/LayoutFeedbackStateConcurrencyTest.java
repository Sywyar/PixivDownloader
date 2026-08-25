package top.sywyar.pixivdownload.download.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.download.request.LayoutFeedbackCommandRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("布局调查状态原子写与并发")
class LayoutFeedbackStateConcurrencyTest extends LayoutFeedbackStateStoreTestSupport {
    /* ============================================================
       原子写
    ============================================================ */

    @Test
    @DisplayName("成功写入后正式文件可严格解析且与内存快照一致")
    void persistedFileMatchesSnapshot() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(submitted(), NOW);
        store.apply(recordSeen("pixiv-batch-landscape"), NOW);

        String persisted = Files.readString(stateFile(), StandardCharsets.UTF_8);
        assertThat(persisted).contains("\"schemaVersion\":2").contains("\"revision\":2");
        LayoutFeedbackStateSnapshot reloaded = new LayoutFeedbackStateStore(stateFile()).snapshot();
        assertThat(reloaded).isEqualTo(store.snapshot());
    }

    @Test
    @DisplayName("写入失败时旧快照保留且临时文件被清理")
    void failedWriteKeepsOldSnapshotAndCleansTemp() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(submitted(), NOW);
        // 让状态目录变成普通文件：createDirectories 必然抛 IOException（跨平台确定）。
        // 注意用 record_seen（必然产生变化并触发持久化；never 对 submitted 是 no-op 不写盘）。
        Path parent = stateFile().getParent();
        Files.delete(stateFile());
        Files.delete(parent);
        Files.writeString(parent, "not a directory", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> store.apply(recordSeen("pixiv-batch-landscape"), NOW))
                .isInstanceOf(IOException.class);

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(snapshot.revision()).isEqualTo(1);
        try (var stream = Files.list(tempDir.resolve("state"))) {
            assertThat(stream.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".tmp")))
                    .as("失败后不得留下临时文件")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("degraded store 的写入被拒绝")
    void degradedStoreRejectsWrites() throws IOException {
        Files.createDirectories(stateFile());
        Files.delete(stateFile());
        Files.createDirectory(stateFile());
        LayoutFeedbackStateStore store = store();
        assertThat(store.degraded()).isTrue();

        assertThatThrownBy(() -> store.apply(submitted(), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("并发写在同一 JVM 内串行：全部命令最终生效且无丢失")
    void concurrentWritesSerialize() throws Exception {
        LayoutFeedbackStateStore store = store();
        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger conflicts = new AtomicInteger();
        String[] layouts = {"pixiv-batch-landscape", "pixiv-batch-portrait", "pixiv-batch-alt"};
        for (int i = 0; i < threads; i++) {
            String layout = layouts[i % layouts.length];
            executor.submit(() -> {
                try {
                    start.await();
                    store.apply(recordSeen(layout), NOW);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.seen().keySet()).containsExactlyInAnyOrder(layouts);
        assertThat(snapshot.revision()).isEqualTo(3);
        assertThat(conflicts.get()).isZero();
    }

    @Test
    @DisplayName("并发 snooze 串行后 snoozedUntil 不缩短（至少最后一次处理的 7 天）")
    void concurrentSnoozesNeverShorten() throws Exception {
        LayoutFeedbackStateStore store = store();
        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    store.apply(snooze(), NOW);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.SNOOZED);
        assertThat(snapshot.state(SURVEY_ID).snoozedUntil())
                .as("并发 snooze 不缩短，至少 NOW + 7 天")
                .isGreaterThanOrEqualTo(NOW + LayoutFeedbackStateStore.SNOOZE_MILLIS);
    }

    @Test
    @DisplayName("snooze 与 submitted 并发：submitted 最终胜出")
    void concurrentSnoozeAndSubmittedSubmittedWins() throws Exception {
        LayoutFeedbackStateStore store = store();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        executor.submit(() -> {
            try {
                start.await();
                store.apply(snooze(), NOW);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        });
        executor.submit(() -> {
            try {
                start.await();
                store.apply(submitted(), NOW);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(store.snapshot().state(SURVEY_ID).status())
                .isEqualTo(LayoutFeedbackDecision.SUBMITTED);
    }

    @Test
    @DisplayName("never 与 submitted 并发：submitted 最终胜出")
    void concurrentNeverAndSubmittedSubmittedWins() throws Exception {
        LayoutFeedbackStateStore store = store();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        executor.submit(() -> {
            try {
                start.await();
                store.apply(never(), NOW);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        });
        executor.submit(() -> {
            try {
                start.await();
                store.apply(submitted(), NOW);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(store.snapshot().state(SURVEY_ID).status())
                .isEqualTo(LayoutFeedbackDecision.SUBMITTED);
    }

    @Test
    @DisplayName("record_seen 与 submitted 并发：submitted 保留，seen 正常合并")
    void concurrentRecordSeenAndSubmitted() throws Exception {
        LayoutFeedbackStateStore store = store();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        executor.submit(() -> {
            try {
                start.await();
                store.apply(recordSeen("pixiv-batch-landscape"), NOW);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        });
        executor.submit(() -> {
            try {
                start.await();
                store.apply(submitted(), NOW);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(snapshot.seen()).containsKey("pixiv-batch-landscape");
    }

    @Test
    @DisplayName("旧 Survey A 与新 Survey B 命令并发：各自 states 条目独立，不互相覆盖")
    void concurrentDifferentSurveyCommandsIndependent() throws Exception {
        LayoutFeedbackStateStore store = store();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        executor.submit(() -> {
            try {
                start.await();
                store.apply(submitted(), NOW);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        });
        executor.submit(() -> {
            try {
                start.await();
                store.apply(command(OTHER_SURVEY_ID, "never", null), NOW);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(snapshot.state(OTHER_SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.NEVER);
    }

}
