package top.sywyar.pixivdownload.download.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.request.LayoutFeedbackCommandRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        String[] layouts = {"pixiv-batch-landscape", "pixiv-batch-portrait", "pixiv-batch-alt"};
        applyConcurrently(store, List.of(
                recordSeen(layouts[0]), recordSeen(layouts[1]), recordSeen(layouts[2]),
                recordSeen(layouts[0]), recordSeen(layouts[1]), recordSeen(layouts[2])));

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.seen().keySet()).containsExactlyInAnyOrder(layouts);
        assertThat(snapshot.revision()).isEqualTo(3);
    }

    @Test
    @DisplayName("并发 snooze 串行后 snoozedUntil 不缩短（至少最后一次处理的 7 天）")
    void concurrentSnoozesNeverShorten() throws Exception {
        LayoutFeedbackStateStore store = store();
        applyConcurrently(store, Collections.nCopies(6, snooze()));

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
        applyConcurrently(store, List.of(snooze(), submitted()));

        assertThat(store.snapshot().state(SURVEY_ID).status())
                .isEqualTo(LayoutFeedbackDecision.SUBMITTED);
    }

    @Test
    @DisplayName("never 与 submitted 并发：submitted 最终胜出")
    void concurrentNeverAndSubmittedSubmittedWins() throws Exception {
        LayoutFeedbackStateStore store = store();
        applyConcurrently(store, List.of(never(), submitted()));

        assertThat(store.snapshot().state(SURVEY_ID).status())
                .isEqualTo(LayoutFeedbackDecision.SUBMITTED);
    }

    @Test
    @DisplayName("record_seen 与 submitted 并发：submitted 保留，seen 正常合并")
    void concurrentRecordSeenAndSubmitted() throws Exception {
        LayoutFeedbackStateStore store = store();
        applyConcurrently(store, List.of(recordSeen("pixiv-batch-landscape"), submitted()));

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(snapshot.seen()).containsKey("pixiv-batch-landscape");
    }

    @Test
    @DisplayName("旧 Survey A 与新 Survey B 命令并发：各自 states 条目独立，不互相覆盖")
    void concurrentDifferentSurveyCommandsIndependent() throws Exception {
        LayoutFeedbackStateStore store = store();
        applyConcurrently(store, List.of(submitted(), command(OTHER_SURVEY_ID, "never", null)));

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(snapshot.state(OTHER_SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.NEVER);
    }

    private void applyConcurrently(LayoutFeedbackStateStore store, List<LayoutFeedbackCommandRequest> commands)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(commands.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (LayoutFeedbackCommandRequest command : commands) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return store.apply(command, NOW);
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).as("并发写入线程应结束").isTrue();
        }
    }

}
