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

@DisplayName("布局调查状态加载与迁移")
class LayoutFeedbackStateStoreTest extends LayoutFeedbackStateStoreTestSupport {
    /* ============================================================
       C. 加载
    ============================================================ */

    @Test
    @DisplayName("懒加载：构造不访问磁盘、不创建目录、不隔离损坏文件")
    void constructorDoesNotTouchDisk() throws IOException {
        Path missingParent = tempDir.resolve("never-created").resolve("state");
        Path file = missingParent.resolve("layout-feedback-state.json");
        Path corrupt = tempDir.resolve("corrupt-state.json");
        Files.createDirectories(corrupt.getParent());
        Files.writeString(corrupt, "{not json", StandardCharsets.UTF_8);
        LayoutFeedbackStateStore onCorrupt = new LayoutFeedbackStateStore(corrupt);

        new LayoutFeedbackStateStore(file);

        assertThat(Files.exists(file)).as("构造不得创建状态文件").isFalse();
        assertThat(Files.exists(missingParent)).as("构造不得创建父目录").isFalse();
        assertThat(Files.exists(corrupt)).as("构造不得隔离损坏文件").isTrue();
        assertThat(corruptFilesOf(corrupt)).isEmpty();
        assertThat(onCorrupt.degraded()).isFalse();
    }

    @Test
    @DisplayName("首次 snapshot 才触发加载：损坏文件在首次访问前保持原样")
    void firstSnapshotTriggersLoad() throws IOException {
        Path file = stateFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);
        LayoutFeedbackStateStore store = store();

        assertThat(Files.exists(file)).as("构造后损坏文件未被移动").isTrue();
        assertThat(corruptFiles()).isEmpty();

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();

        assertThat(snapshot.revision()).isZero();
        assertThat(Files.exists(file)).as("首次访问后损坏文件被隔离").isFalse();
        assertThat(corruptFiles()).hasSize(1);
    }

    @Test
    @DisplayName("多线程同时首次 snapshot 只加载一次，得到同一快照，不重复隔离")
    void concurrentFirstSnapshotLoadsOnce() throws Exception {
        Path file = stateFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);
        LayoutFeedbackStateStore store = store();

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicReference<LayoutFeedbackStateSnapshot> first =
                new java.util.concurrent.atomic.AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    LayoutFeedbackStateSnapshot snapshot = store.snapshot();
                    first.compareAndSet(null, snapshot);
                    if (first.get() != snapshot) {
                        throw new AssertionError("并发首次加载必须返回同一快照实例");
                    }
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

        assertThat(corruptFiles()).as("并发首次加载只隔离一次").hasSize(1);
        assertThat(store.degraded()).isFalse();
        assertThat(store.snapshot()).isSameAs(first.get());
    }

    @Test
    @DisplayName("v2 空文档：空 states / seen、revision=0、store 可用")
    void emptyV2DocumentLoads() throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(),
                "{\"schemaVersion\":2,\"revision\":0,\"states\":{},\"seen\":{}}",
                StandardCharsets.UTF_8);

        LayoutFeedbackStateStore store = store();

        assertThat(store.degraded()).isFalse();
        assertThat(store.snapshot().revision()).isZero();
        assertThat(store.snapshot().states()).isEmpty();
        assertThat(store.snapshot().seen()).isEmpty();
    }

    @Test
    @DisplayName("v2 多 Survey 状态 round-trip 加载，落盘内容为小写 status")
    void v2FileRoundTrips() throws IOException {
        LayoutFeedbackStateStore first = store();
        first.apply(submitted(), NOW);
        first.apply(command(OTHER_SURVEY_ID, "snooze", null), NOW);

        String persisted = Files.readString(stateFile(), StandardCharsets.UTF_8);
        assertThat(persisted).contains("\"schemaVersion\":2");
        assertThat(persisted).contains("\"status\":\"submitted\"");
        assertThat(persisted).contains("\"status\":\"snoozed\"");
        assertThat(persisted).doesNotContain("SUBMITTED");

        LayoutFeedbackStateStore second = new LayoutFeedbackStateStore(stateFile());
        LayoutFeedbackStateSnapshot snapshot = second.snapshot();
        assertThat(snapshot.state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(snapshot.state(OTHER_SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.SNOOZED);
    }

    @Test
    @DisplayName("v1 状态文件迁移：state 放入 states[state.surveyId]，seen / revision 保留")
    void v1StateMigratesToStatesMap() throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(),
                "{\"schemaVersion\":1,\"revision\":5,"
                        + "\"state\":{\"surveyId\":\"" + SURVEY_ID
                        + "\",\"status\":\"SUBMITTED\",\"updatedAt\":1,\"snoozedUntil\":0},"
                        + "\"seen\":{\"pixiv-batch-landscape\":{\"firstSeenAt\":1,\"lastSeenAt\":2}}}",
                StandardCharsets.UTF_8);

        LayoutFeedbackStateStore store = store();

        assertThat(store.degraded()).isFalse();
        assertThat(corruptFiles()).as("v1 文件不得被隔离").isEmpty();
        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.revision()).as("v1 revision 保留").isEqualTo(5);
        assertThat(snapshot.state(SURVEY_ID).status())
                .as("旧大写枚举继续兼容")
                .isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(snapshot.seen().get("pixiv-batch-landscape").firstSeenAt())
                .as("v1 seen 保留")
                .isEqualTo(1);
        assertThat(snapshot.seen().get("pixiv-batch-landscape").lastSeenAt())
                .as("v1 seen 保留")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("v1 状态文件下一次实际写入保存为 schemaVersion=2 的 states map")
    void v1MigratesToV2OnNextWrite() throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(),
                "{\"schemaVersion\":1,\"revision\":1,"
                        + "\"state\":{\"surveyId\":\"" + SURVEY_ID
                        + "\",\"status\":\"NEVER\",\"updatedAt\":1,\"snoozedUntil\":0},"
                        + "\"seen\":{}}",
                StandardCharsets.UTF_8);
        LayoutFeedbackStateStore store = store();
        assertThat(store.snapshot().state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.NEVER);

        store.apply(recordSeen("pixiv-batch-landscape"), NOW);

        String persisted = Files.readString(stateFile(), StandardCharsets.UTF_8);
        assertThat(persisted).contains("\"schemaVersion\":2");
        assertThat(persisted).contains("\"states\"");
        assertThat(persisted).contains("\"status\":\"never\"");
        assertThat(persisted).doesNotContain("\"state\":");
        assertThat(persisted).doesNotContain("NEVER");
        LayoutFeedbackStateStore reloaded = new LayoutFeedbackStateStore(stateFile());
        assertThat(reloaded.snapshot().state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.NEVER);
    }

    @Test
    @DisplayName("v1 state=null 且无 states 字段：正常迁移为空 states，seen / revision 保留")
    void v1NullStateWithoutStatesMigratesToEmptyStates() throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(),
                "{\"schemaVersion\":1,\"revision\":3,\"state\":null,\"seen\":{}}",
                StandardCharsets.UTF_8);

        LayoutFeedbackStateStore store = store();

        assertThat(store.degraded()).isFalse();
        assertThat(corruptFiles()).as("v1 state=null 文件不得被隔离").isEmpty();
        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.revision()).as("v1 revision 保留").isEqualTo(3);
        assertThat(snapshot.states()).as("迁移为空 states").isEmpty();
        assertThat(snapshot.seen()).as("v1 seen 保留").isEmpty();
    }

    @Test
    @DisplayName("v1 同时含 state 和 states 字段：歧义协议拒绝并隔离，Store 空快照继续可用")
    void v1WithStateAndStatesQuarantined() throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(),
                "{\"schemaVersion\":1,\"revision\":1,"
                        + "\"state\":{\"surveyId\":\"" + SURVEY_ID
                        + "\",\"status\":\"SUBMITTED\",\"updatedAt\":1,\"snoozedUntil\":0},"
                        + "\"states\":{},\"seen\":{}}",
                StandardCharsets.UTF_8);

        LayoutFeedbackStateStore store = store();

        assertThat(store.degraded()).isFalse();
        assertThat(corruptFiles()).as("歧义 v1 文件必须按损坏隔离").hasSize(1);
        assertThat(Files.exists(stateFile())).as("歧义文件必须被移走").isFalse();
        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.states()).as("Store 使用空快照").isEmpty();
        assertThat(snapshot.revision()).isZero();
        // 隔离后 Store 继续可用（目录可写时）。
        store.apply(recordSeen("pixiv-batch-landscape"), NOW);
        assertThat(store.snapshot().seen()).containsKey("pixiv-batch-landscape");
    }

    @Test
    @DisplayName("v1 state=null 但含 states 字段（含空 states）：歧义协议拒绝并隔离")
    void v1NullStateWithStatesQuarantined() throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(),
                "{\"schemaVersion\":1,\"revision\":1,\"state\":null,\"states\":{},\"seen\":{}}",
                StandardCharsets.UTF_8);

        LayoutFeedbackStateStore store = store();

        assertThat(store.degraded()).isFalse();
        assertThat(corruptFiles()).as("v1 含 states 字段（即使为空）必须按损坏隔离").hasSize(1);
        assertThat(Files.exists(stateFile())).as("歧义文件必须被移走").isFalse();
        assertThat(store.snapshot().states()).isEmpty();
    }

    @Test
    @DisplayName("v2 同时含非空 state 和 states 字段：歧义协议拒绝并隔离，Store 空快照")
    void v2WithStateAndStatesQuarantined() throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(),
                "{\"schemaVersion\":2,\"revision\":1,"
                        + "\"state\":{\"surveyId\":\"" + SURVEY_ID
                        + "\",\"status\":\"SUBMITTED\",\"updatedAt\":1,\"snoozedUntil\":0},"
                        + "\"states\":{" + stateJson(SURVEY_ID, 1) + "},\"seen\":{}}",
                StandardCharsets.UTF_8);

        LayoutFeedbackStateStore store = store();

        assertThat(store.degraded()).isFalse();
        assertThat(corruptFiles()).as("v2 含非空 state 字段必须按损坏隔离").hasSize(1);
        assertThat(Files.exists(stateFile())).as("歧义文件必须被移走").isFalse();
        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.states()).as("Store 使用空快照").isEmpty();
        assertThat(snapshot.revision()).isZero();
    }

    @Test
    @DisplayName("v2 非空 state、空 states 字段：仍拒绝并隔离")
    void v2NonEmptyStateWithEmptyStatesQuarantined() throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(),
                "{\"schemaVersion\":2,\"revision\":1,"
                        + "\"state\":{\"surveyId\":\"" + SURVEY_ID
                        + "\",\"status\":\"SUBMITTED\",\"updatedAt\":1,\"snoozedUntil\":0},"
                        + "\"states\":{},\"seen\":{}}",
                StandardCharsets.UTF_8);

        LayoutFeedbackStateStore store = store();

        assertThat(store.degraded()).isFalse();
        assertThat(corruptFiles()).as("v2 state 非空、states 为空仍必须拒绝").hasSize(1);
        assertThat(Files.exists(stateFile())).as("歧义文件必须被移走").isFalse();
        assertThat(store.snapshot().states()).isEmpty();
    }

    @Test
    @DisplayName("直接构造 LayoutFeedbackStateDocument：v1 + states 非 null 抛异常，v2 + state 非 null 抛异常")
    void documentMutualExclusionRejected() {
        LayoutFeedbackStateEntry entry = new LayoutFeedbackStateEntry(
                SURVEY_ID, LayoutFeedbackDecision.SUBMITTED, 1L, 0L);
        // v1 + states 非 null（含空 map）→ 拒绝。
        assertThatThrownBy(() -> new LayoutFeedbackStateDocument(1, 0L, null, Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LayoutFeedbackStateDocument(1, 0L, entry, Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        // v2 + state 非 null → 拒绝。
        assertThatThrownBy(() -> new LayoutFeedbackStateDocument(2, 0L, entry, Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        // v1 正常形态：state 可空，states 必须 null。
        assertThat(new LayoutFeedbackStateDocument(1, 0L, null, null, Map.of()).states()).isEmpty();
        assertThat(new LayoutFeedbackStateDocument(1, 0L, entry, null, Map.of()).states())
                .containsEntry(SURVEY_ID, entry);
        // v2 正常形态：state 必须 null。
        assertThat(new LayoutFeedbackStateDocument(2, 0L, null, Map.of(SURVEY_ID, entry), Map.of())
                .states()).containsEntry(SURVEY_ID, entry);
    }

    @Test
    @DisplayName("文件不存在 → 空状态、revision=0、store 可用")
    void missingFileYieldsEmptyUsableStore() {
        LayoutFeedbackStateStore store = store();

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.revision()).isZero();
        assertThat(snapshot.states()).isEmpty();
        assertThat(snapshot.seen()).isEmpty();
        assertThat(store.degraded()).isFalse();
    }

    @Test
    @DisplayName("损坏 JSON → 隔离为 corrupt 文件、空状态、store 继续可用")
    void corruptJsonQuarantinedAndStoreUsable() throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(), "{not json", StandardCharsets.UTF_8);

        LayoutFeedbackStateStore store = store();

        assertThat(store.degraded()).isFalse();
        assertThat(store.snapshot().revision()).isZero();
        assertThat(Files.exists(stateFile())).as("损坏文件必须被移走").isFalse();
        assertThat(corruptFiles()).hasSize(1);
        store.apply(submitted(), NOW);
        assertThat(store.snapshot().state(SURVEY_ID).status())
                .isEqualTo(LayoutFeedbackDecision.SUBMITTED);
    }

    @Test
    @DisplayName("未知 schemaVersion → 按损坏处理")
    void wrongSchemaVersionQuarantined() throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(),
                "{\"schemaVersion\":9,\"revision\":0,\"states\":{},\"seen\":{}}",
                StandardCharsets.UTF_8);

        LayoutFeedbackStateStore store = store();

        assertThat(store.snapshot().revision()).isZero();
        assertThat(store.degraded()).isFalse();
        assertThat(corruptFiles()).hasSize(1);
    }

    @Test
    @DisplayName("未知布局 ID 的 seen → 按损坏处理")
    void unknownLayoutInSeenQuarantined() throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(),
                "{\"schemaVersion\":2,\"revision\":3,\"states\":{},"
                        + "\"seen\":{\"pixiv-batch-unknown\":{\"firstSeenAt\":1,\"lastSeenAt\":1}}}",
                StandardCharsets.UTF_8);

        LayoutFeedbackStateStore store = store();

        assertThat(store.snapshot().revision()).isZero();
        assertThat(corruptFiles()).hasSize(1);
    }

    @Test
    @DisplayName("超大文件 → 按损坏处理，不读取无限大内容")
    void oversizedFileQuarantined() throws IOException {
        Files.createDirectories(stateFile().getParent());
        byte[] oversized = new byte[(int) LayoutFeedbackStateStore.MAX_STATE_FILE_BYTES + 1];
        Files.write(stateFile(), oversized);

        LayoutFeedbackStateStore store = store();

        assertThat(store.snapshot().revision()).isZero();
        assertThat(store.degraded()).isFalse();
        assertThat(corruptFiles()).hasSize(1);
    }

    @Test
    @DisplayName("状态路径不是常规文件 → store degraded，快照为空")
    void nonRegularFileDegradesStore() throws IOException {
        Files.createDirectories(stateFile());
        Files.delete(stateFile());
        Files.createDirectory(stateFile());

        LayoutFeedbackStateStore store = store();

        assertThat(store.degraded()).isTrue();
        assertThat(store.snapshot().revision()).isZero();
        assertThat(store.snapshot().states()).isEmpty();
    }

}
