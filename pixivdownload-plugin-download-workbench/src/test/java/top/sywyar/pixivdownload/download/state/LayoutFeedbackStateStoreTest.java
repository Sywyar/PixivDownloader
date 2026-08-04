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

@DisplayName("布局调查服务端状态存储")
class LayoutFeedbackStateStoreTest {

    private static final String SURVEY_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String OTHER_SURVEY_ID = "aaaaaaaa-bbbb-cccc-dddd-ffffffffffff";
    private static final long NOW = 1_785_000_000_000L;

    @TempDir
    Path tempDir;

    private Path stateFile() {
        return tempDir.resolve("state/download-workbench/layout-feedback-state.json");
    }

    private LayoutFeedbackStateStore store() {
        return new LayoutFeedbackStateStore(stateFile());
    }

    private LayoutFeedbackCommandRequest command(long expectedRevision, String surveyId,
                                                 String command, List<String> layoutIds) {
        return new LayoutFeedbackCommandRequest(expectedRevision, surveyId, command, layoutIds);
    }

    private LayoutFeedbackCommandRequest recordSeen(long expectedRevision, String... layoutIds) {
        return command(expectedRevision, SURVEY_ID, "record_seen", List.of(layoutIds));
    }

    private LayoutFeedbackCommandRequest submitted(long expectedRevision) {
        return command(expectedRevision, SURVEY_ID, "submitted", null);
    }

    private LayoutFeedbackCommandRequest never(long expectedRevision) {
        return command(expectedRevision, SURVEY_ID, "never", null);
    }

    private LayoutFeedbackCommandRequest snooze(long expectedRevision) {
        return command(expectedRevision, SURVEY_ID, "snooze", null);
    }

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
    @DisplayName("小写线格式状态文件 round-trip，落盘内容为小写 status")
    void lowercaseWireFileRoundTrips() throws IOException {
        LayoutFeedbackStateStore first = store();
        first.apply(submitted(0), NOW);

        String persisted = Files.readString(stateFile(), StandardCharsets.UTF_8);

        assertThat(persisted).contains("\"status\":\"submitted\"");
        assertThat(persisted).doesNotContain("SUBMITTED");
        LayoutFeedbackStateStore second = new LayoutFeedbackStateStore(stateFile());
        assertThat(second.snapshot().state().status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
    }

    @Test
    @DisplayName("f4d587b6 风格旧大写状态文件可正常加载，不隔离")
    void legacyUpperCaseFileLoadsCompatibly() throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(),
                "{\"schemaVersion\":1,\"revision\":1,"
                        + "\"state\":{\"surveyId\":\"" + SURVEY_ID
                        + "\",\"status\":\"SUBMITTED\",\"updatedAt\":1,\"snoozedUntil\":0},"
                        + "\"seen\":{}}",
                StandardCharsets.UTF_8);

        LayoutFeedbackStateStore store = store();

        assertThat(store.degraded()).isFalse();
        assertThat(corruptFiles()).as("旧大写文件不得被隔离").isEmpty();
        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.revision()).isEqualTo(1);
        assertThat(snapshot.state().status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(snapshot.state().surveyId()).isEqualTo(SURVEY_ID);
    }

    @Test
    @DisplayName("旧大写文件在下一次实际状态变化后重新写为小写线格式")
    void legacyUpperCaseFileMigratesToLowercaseOnNextWrite() throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(),
                "{\"schemaVersion\":1,\"revision\":1,"
                        + "\"state\":{\"surveyId\":\"" + SURVEY_ID
                        + "\",\"status\":\"NEVER\",\"updatedAt\":1,\"snoozedUntil\":0},"
                        + "\"seen\":{}}",
                StandardCharsets.UTF_8);
        LayoutFeedbackStateStore store = store();
        assertThat(store.snapshot().state().status()).isEqualTo(LayoutFeedbackDecision.NEVER);

        store.apply(recordSeen(1, "pixiv-batch-landscape"), NOW);

        String persisted = Files.readString(stateFile(), StandardCharsets.UTF_8);
        assertThat(persisted).contains("\"status\":\"never\"");
        assertThat(persisted).doesNotContain("NEVER");
        LayoutFeedbackStateStore reloaded = new LayoutFeedbackStateStore(stateFile());
        assertThat(reloaded.snapshot().state().status()).isEqualTo(LayoutFeedbackDecision.NEVER);
    }

    @Test
    @DisplayName("旧大写 snoozed 文件兼容读取且 snoozedUntil 保留")
    void legacyUpperCaseSnoozedFileLoads() throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(),
                "{\"schemaVersion\":1,\"revision\":2,"
                        + "\"state\":{\"surveyId\":\"" + SURVEY_ID
                        + "\",\"status\":\"SNOOZED\",\"updatedAt\":1,\"snoozedUntil\":12345},"
                        + "\"seen\":{}}",
                StandardCharsets.UTF_8);

        LayoutFeedbackStateSnapshot snapshot = store().snapshot();

        assertThat(snapshot.state().status()).isEqualTo(LayoutFeedbackDecision.SNOOZED);
        assertThat(snapshot.state().snoozedUntil()).isEqualTo(12345);
        assertThat(corruptFiles()).isEmpty();
    }

    @Test
    @DisplayName("文件不存在 → 空状态、revision=0、store 可用")
    void missingFileYieldsEmptyUsableStore() {
        LayoutFeedbackStateStore store = store();

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.revision()).isZero();
        assertThat(snapshot.state()).isNull();
        assertThat(snapshot.seen()).isEmpty();
        assertThat(store.degraded()).isFalse();
    }

    @Test
    @DisplayName("合法文件 round-trip 加载")
    void validFileRoundTrips() throws IOException {
        LayoutFeedbackStateStore first = store();
        first.apply(submitted(0), NOW);
        LayoutFeedbackStateStore second = new LayoutFeedbackStateStore(stateFile());

        LayoutFeedbackStateSnapshot snapshot = second.snapshot();
        assertThat(snapshot.revision()).isEqualTo(1);
        assertThat(snapshot.state().status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(snapshot.state().surveyId()).isEqualTo(SURVEY_ID);
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
        store.apply(submitted(0), NOW);
        assertThat(store.snapshot().state().status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
    }

    @Test
    @DisplayName("schemaVersion 错误 → 按损坏处理")
    void wrongSchemaVersionQuarantined() throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(),
                "{\"schemaVersion\":2,\"revision\":0,\"state\":null,\"seen\":{}}",
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
                "{\"schemaVersion\":1,\"revision\":3,\"state\":null,"
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
        assertThat(store.snapshot().state()).isNull();
    }

    @Test
    @DisplayName("state 与 seen 来自同一不可变快照")
    void stateAndSeenFromSameSnapshot() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(recordSeen(0, "pixiv-batch-landscape"), NOW);

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.seen()).containsKey("pixiv-batch-landscape");
        assertThat(snapshot.revision()).isEqualTo(1);
        assertThat(store.snapshot()).isSameAs(snapshot);
    }

    /* ============================================================
       D. CAS
    ============================================================ */

    @Test
    @DisplayName("正确 revision 命令成功并递增 revision")
    void correctRevisionApplies() throws IOException {
        LayoutFeedbackStateStore store = store();

        LayoutFeedbackStateStore.ApplyResult result = store.apply(submitted(0), NOW);

        assertThat(result.status()).isEqualTo(LayoutFeedbackStateStore.ApplyStatus.APPLIED);
        assertThat(result.snapshot().revision()).isEqualTo(1);
        assertThat(store.snapshot().revision()).isEqualTo(1);
    }

    @Test
    @DisplayName("stale revision 返回 409 且不写文件")
    void staleRevisionConflicts() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(submitted(0), NOW);

        LayoutFeedbackStateStore.ApplyResult conflict = store.apply(never(0), NOW);

        assertThat(conflict.status()).isEqualTo(LayoutFeedbackStateStore.ApplyStatus.CONFLICT);
        assertThat(conflict.snapshot().revision()).isEqualTo(1);
        assertThat(store.snapshot().state().status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        String persisted = Files.readString(stateFile(), StandardCharsets.UTF_8);
        assertThat(persisted).contains("\"revision\":1");
    }

    @Test
    @DisplayName("stale record_seen 不擦除 submitted")
    void staleRecordSeenCannotEraseSubmitted() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(submitted(0), NOW);

        LayoutFeedbackStateStore.ApplyResult conflict = store.apply(recordSeen(0, "pixiv-batch-portrait"), NOW);

        assertThat(conflict.status()).isEqualTo(LayoutFeedbackStateStore.ApplyStatus.CONFLICT);
        assertThat(store.snapshot().state().status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(store.snapshot().seen()).doesNotContainKey("pixiv-batch-portrait");
    }

    @Test
    @DisplayName("stale record_seen 不擦除 never")
    void staleRecordSeenCannotEraseNever() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(never(0), NOW);

        LayoutFeedbackStateStore.ApplyResult conflict = store.apply(recordSeen(0, "pixiv-batch-landscape"), NOW);

        assertThat(conflict.status()).isEqualTo(LayoutFeedbackStateStore.ApplyStatus.CONFLICT);
        assertThat(store.snapshot().state().status()).isEqualTo(LayoutFeedbackDecision.NEVER);
    }

    @Test
    @DisplayName("stale record_seen 不擦除其它布局 seen")
    void staleRecordSeenKeepsOtherSeen() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(recordSeen(0, "pixiv-batch-landscape"), NOW);

        LayoutFeedbackStateStore.ApplyResult conflict =
                store.apply(recordSeen(0, "pixiv-batch-portrait", "pixiv-batch-alt"), NOW);

        assertThat(conflict.status()).isEqualTo(LayoutFeedbackStateStore.ApplyStatus.CONFLICT);
        assertThat(store.snapshot().seen().keySet())
                .containsExactly("pixiv-batch-landscape");
    }

    @Test
    @DisplayName("每次实际改变后 revision 正确递增，no-op 保持 revision")
    void revisionIncrementsOnChangeOnly() throws IOException {
        LayoutFeedbackStateStore store = store();
        assertThat(store.apply(submitted(0), NOW).snapshot().revision()).isEqualTo(1);
        assertThat(store.apply(submitted(1), NOW).snapshot().revision())
                .as("重复 submitted 幂等 no-op，revision 不变")
                .isEqualTo(1);
        assertThat(store.apply(recordSeen(1, "pixiv-batch-landscape"), NOW)
                .snapshot().revision()).isEqualTo(2);
    }

    /* ============================================================
       E. 状态单调性
    ============================================================ */

    @Test
    @DisplayName("submitted 不被 never 降级")
    void submittedNotDowngradedByNever() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(submitted(0), NOW);

        LayoutFeedbackStateStore.ApplyResult result = store.apply(never(1), NOW);

        assertThat(result.status()).isEqualTo(LayoutFeedbackStateStore.ApplyStatus.APPLIED);
        assertThat(store.snapshot().state().status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(store.snapshot().revision()).isEqualTo(1);
    }

    @Test
    @DisplayName("submitted 不被 snooze 降级")
    void submittedNotDowngradedBySnooze() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(submitted(0), NOW);

        store.apply(snooze(1), NOW);

        assertThat(store.snapshot().state().status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
    }

    @Test
    @DisplayName("never 可被 submitted 升级")
    void neverUpgradedBySubmitted() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(never(0), NOW);

        store.apply(submitted(1), NOW);

        assertThat(store.snapshot().state().status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(store.snapshot().revision()).isEqualTo(2);
    }

    @Test
    @DisplayName("never 不被 snooze 降级")
    void neverNotDowngradedBySnooze() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(never(0), NOW);

        store.apply(snooze(1), NOW);

        assertThat(store.snapshot().state().status()).isEqualTo(LayoutFeedbackDecision.NEVER);
    }

    @Test
    @DisplayName("snooze 可被 never 升级")
    void snoozeUpgradedByNever() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(snooze(0), NOW);

        store.apply(never(1), NOW);

        assertThat(store.snapshot().state().status()).isEqualTo(LayoutFeedbackDecision.NEVER);
    }

    @Test
    @DisplayName("snooze 可被 submitted 升级")
    void snoozeUpgradedBySubmitted() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(snooze(0), NOW);

        store.apply(submitted(1), NOW);

        assertThat(store.snapshot().state().status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
    }

    @Test
    @DisplayName("重复 snooze 使用新的服务端时间，snoozedUntil 单调不倒退")
    void repeatedSnoozeRefreshesServerTime() throws IOException {
        LayoutFeedbackStateStore store = store();
        LayoutFeedbackStateStore.ApplyResult first = store.apply(snooze(0), NOW);
        LayoutFeedbackStateStore.ApplyResult second = store.apply(snooze(1), NOW + 1000);

        assertThat(first.snapshot().state().snoozedUntil()).isEqualTo(NOW + LayoutFeedbackStateStore.SNOOZE_MILLIS);
        assertThat(second.snapshot().state().snoozedUntil())
                .isEqualTo(NOW + 1000 + LayoutFeedbackStateStore.SNOOZE_MILLIS);
        assertThat(second.snapshot().revision()).isEqualTo(2);
    }

    /* ============================================================
       服务端墙钟回拨防御
    ============================================================ */

    @Test
    @DisplayName("重复 snooze，第二次 now 比第一次早 1 小时：snoozedUntil 不缩短、updatedAt 不倒退、不抛异常")
    void repeatedSnoozeWithRollbackDoesNotShorten() throws IOException {
        LayoutFeedbackStateStore store = store();
        LayoutFeedbackStateStore.ApplyResult first = store.apply(snooze(0), NOW);

        LayoutFeedbackStateStore.ApplyResult second = store.apply(snooze(1), NOW - 60 * 60 * 1000);

        assertThat(second.status()).isEqualTo(LayoutFeedbackStateStore.ApplyStatus.APPLIED);
        assertThat(second.snapshot().state().snoozedUntil())
                .as("墙钟回拨不得缩短已有 snooze")
                .isEqualTo(NOW + LayoutFeedbackStateStore.SNOOZE_MILLIS);
        assertThat(second.snapshot().state().updatedAt())
                .as("updatedAt 不得倒退")
                .isEqualTo(NOW);
        assertThat(second.snapshot().revision()).isEqualTo(1);
    }

    @Test
    @DisplayName("重复 snooze，回拨后 proposedUntil 仍小于旧值：no-op、revision 不变、文件字节不变")
    void repeatedSnoozeRollbackIsNoOpAndDoesNotRewrite() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(snooze(0), NOW);
        String before = Files.readString(stateFile(), StandardCharsets.UTF_8);
        long revisionBefore = store.snapshot().revision();

        LayoutFeedbackStateStore.ApplyResult result = store.apply(snooze(1), NOW - 60 * 60 * 1000);

        assertThat(result.status()).isEqualTo(LayoutFeedbackStateStore.ApplyStatus.APPLIED);
        assertThat(store.snapshot().revision()).as("no-op 保持 revision").isEqualTo(revisionBefore);
        String after = Files.readString(stateFile(), StandardCharsets.UTF_8);
        assertThat(after).as("no-op 不重写状态文件").isEqualTo(before);
        assertThat(corruptFiles()).isEmpty();
    }

    @Test
    @DisplayName("never → submitted，now 小于 old.updatedAt：submitted 生效且 updatedAt 不倒退")
    void upgradeKeepsUpdatedAtMonotonicUnderRollback() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(never(0), NOW);

        LayoutFeedbackStateStore.ApplyResult result =
                store.apply(submitted(1), NOW - 60 * 60 * 1000);

        assertThat(result.snapshot().state().status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(result.snapshot().state().updatedAt())
                .as("升级后 updatedAt 保持旧值或更大")
                .isEqualTo(NOW);
        assertThat(result.snapshot().revision()).isEqualTo(2);
    }

    @Test
    @DisplayName("record_seen：旧 first=100 / last=200，新 now=50：first/last 保持、no-op、revision 不变")
    void recordSeenDoesNotMoveBackwards() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(recordSeen(0, "pixiv-batch-landscape"), 100);
        store.apply(recordSeen(1, "pixiv-batch-landscape"), 200);
        String before = Files.readString(stateFile(), StandardCharsets.UTF_8);

        LayoutFeedbackStateStore.ApplyResult result = store.apply(recordSeen(2, "pixiv-batch-landscape"), 50);

        LayoutFeedbackSeenEntry entry = result.snapshot().seen().get("pixiv-batch-landscape");
        assertThat(entry.firstSeenAt()).isEqualTo(100);
        assertThat(entry.lastSeenAt()).as("墙钟回拨后 lastSeenAt 不得倒退").isEqualTo(200);
        assertThat(result.snapshot().revision()).isEqualTo(2);
        assertThat(Files.readString(stateFile(), StandardCharsets.UTF_8))
                .as("no-op 不重写状态文件")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("record_seen：旧 last=200，新 now=300：lastSeenAt 前进且 revision +1")
    void recordSeenAdvancesLastSeenAt() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(recordSeen(0, "pixiv-batch-landscape"), 100);
        store.apply(recordSeen(1, "pixiv-batch-landscape"), 200);

        LayoutFeedbackStateStore.ApplyResult result = store.apply(recordSeen(2, "pixiv-batch-landscape"), 300);

        LayoutFeedbackSeenEntry entry = result.snapshot().seen().get("pixiv-batch-landscape");
        assertThat(entry.firstSeenAt()).isEqualTo(100);
        assertThat(entry.lastSeenAt()).isEqualTo(300);
        assertThat(result.snapshot().revision()).isEqualTo(3);
    }

    @Test
    @DisplayName("now 为负数：新 entry / 新状态使用 0，不生成非法时间")
    void negativeNowClampedToZero() throws IOException {
        LayoutFeedbackStateStore store = store();

        LayoutFeedbackStateStore.ApplyResult seen = store.apply(recordSeen(0, "pixiv-batch-landscape"), -500);

        LayoutFeedbackSeenEntry entry = seen.snapshot().seen().get("pixiv-batch-landscape");
        assertThat(entry.firstSeenAt()).isZero();
        assertThat(entry.lastSeenAt()).isZero();
        assertThat(entry.lastSeenAt()).as("lastSeenAt 不得小于 firstSeenAt")
                .isGreaterThanOrEqualTo(entry.firstSeenAt());

        LayoutFeedbackStateStore.ApplyResult state = store.apply(snooze(1), -500);
        assertThat(state.snapshot().state().updatedAt()).isZero();
        assertThat(state.snapshot().state().snoozedUntil())
                .isEqualTo(LayoutFeedbackStateStore.SNOOZE_MILLIS);
    }

    @Test
    @DisplayName("saturatingAdd 语义：near Long.MAX_VALUE 不溢出为负数")
    void saturatingAddNearMaxValueDoesNotOverflow() throws IOException {
        long nearMax = Long.MAX_VALUE - 1000;
        LayoutFeedbackStateStore store = store();

        LayoutFeedbackStateStore.ApplyResult result = store.apply(snooze(0), nearMax);

        assertThat(result.status()).isEqualTo(LayoutFeedbackStateStore.ApplyStatus.APPLIED);
        assertThat(result.snapshot().state().updatedAt()).isEqualTo(nearMax);
        assertThat(result.snapshot().state().snoozedUntil())
                .as("接近 Long.MAX_VALUE 时饱和为 Long.MAX_VALUE，不得溢出为负数")
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("新 Survey ID 不受旧 Survey submitted/never 阻挡，seen 保留")
    void newSurveyIdNotBlockedByOldSurveyState() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(recordSeen(0, "pixiv-batch-landscape"), NOW);
        store.apply(submitted(1), NOW);

        // 旧 Survey 已 submitted，新 Survey 的 never 不被阻挡。
        store.apply(command(2, OTHER_SURVEY_ID, "never", null), NOW);
        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.state().surveyId()).isEqualTo(OTHER_SURVEY_ID);
        assertThat(snapshot.state().status()).isEqualTo(LayoutFeedbackDecision.NEVER);
        assertThat(snapshot.seen().keySet()).containsExactly("pixiv-batch-landscape");

        // 新 Survey 的 never 不被 snooze 降级（no-op，revision 不变）。
        store.apply(command(3, OTHER_SURVEY_ID, "snooze", null), NOW);
        assertThat(store.snapshot().state().status()).isEqualTo(LayoutFeedbackDecision.NEVER);

        // 新 Survey 的 submitted 可升级。
        store.apply(command(3, OTHER_SURVEY_ID, "submitted", null), NOW);
        snapshot = store.snapshot();
        assertThat(snapshot.state().surveyId()).isEqualTo(OTHER_SURVEY_ID);
        assertThat(snapshot.state().status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(snapshot.seen().keySet()).containsExactly("pixiv-batch-landscape");
    }

    @Test
    @DisplayName("GET 语义：旧 Survey 的 state 被新命令替换；seen 与 Survey ID 无关")
    void stateIsPerSurveySeenIsGlobal() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(submitted(0), NOW);

        // 新 Survey 的 record_seen 不触碰旧 Survey state。
        store.apply(command(1, OTHER_SURVEY_ID, "record_seen", List.of("pixiv-batch-alt")), NOW);

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.state().surveyId()).isEqualTo(SURVEY_ID);
        assertThat(snapshot.state().status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(snapshot.seen()).containsKey("pixiv-batch-alt");
    }

    /* ============================================================
       F. seen
    ============================================================ */

    @Test
    @DisplayName("record_seen 首次写入 firstSeenAt=lastSeenAt=now，二次写入保持 firstSeenAt")
    void seenTimestampsMerge() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(recordSeen(0, "pixiv-batch-landscape"), NOW);

        LayoutFeedbackStateStore.ApplyResult second = store.apply(recordSeen(1, "pixiv-batch-landscape"), NOW + 5000);

        LayoutFeedbackSeenEntry entry = second.snapshot().seen().get("pixiv-batch-landscape");
        assertThat(entry.firstSeenAt()).isEqualTo(NOW);
        assertThat(entry.lastSeenAt()).isEqualTo(NOW + 5000);
    }

    @Test
    @DisplayName("record_seen 永不修改 state")
    void recordSeenNeverTouchesState() throws IOException {
        LayoutFeedbackStateStore store = store();

        store.apply(recordSeen(0, "pixiv-batch-landscape"), NOW);

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.state()).isNull();
        assertThat(snapshot.seen().keySet()).containsExactly("pixiv-batch-landscape");
    }

    @Test
    @DisplayName("最多三个布局，全部记录后新增记录不再增加条目")
    void seenLimitedToThreeLayouts() throws IOException {
        LayoutFeedbackStateStore store = store();
        LayoutFeedbackStateStore.ApplyResult result = store.apply(
                recordSeen(0, "pixiv-batch-landscape", "pixiv-batch-portrait", "pixiv-batch-alt"), NOW);

        assertThat(result.status()).isEqualTo(LayoutFeedbackStateStore.ApplyStatus.APPLIED);
        assertThat(result.snapshot().seen()).hasSize(3);
    }

    @Test
    @DisplayName("record_seen 提交后 submitted 状态保持不被擦除")
    void recordSeenAfterSubmittedKeepsSubmitted() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(submitted(0), NOW);

        store.apply(recordSeen(1, "pixiv-batch-landscape"), NOW);

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.state().status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(snapshot.seen()).containsKey("pixiv-batch-landscape");
        assertThat(snapshot.revision()).isEqualTo(2);
    }

    /* ============================================================
       G. 输入校验（命令构造）
    ============================================================ */

    @Test
    @DisplayName("unknown command → 拒绝")
    void unknownCommandRejected() {
        assertThatThrownBy(() -> command(0, SURVEY_ID, "explode", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative revision → 拒绝")
    void negativeRevisionRejected() {
        assertThatThrownBy(() -> command(-1, SURVEY_ID, "never", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("缺 surveyId / 非法 surveyId → 拒绝")
    void invalidSurveyIdRejected() {
        assertThatThrownBy(() -> new LayoutFeedbackCommandRequest(0, null, "never", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LayoutFeedbackCommandRequest(0, "bad id", "never", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("record_seen 无 layoutIds → 拒绝")
    void recordSeenWithoutLayoutIdsRejected() {
        assertThatThrownBy(() -> command(0, SURVEY_ID, "record_seen", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command(0, SURVEY_ID, "record_seen", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("record_seen 未知布局 / 重复布局 → 拒绝")
    void recordSeenInvalidLayoutsRejected() {
        assertThatThrownBy(() -> recordSeen(0, "pixiv-batch-nope"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recordSeen(0, "pixiv-batch-landscape", "pixiv-batch-landscape"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recordSeen(0, "a", "b", "c", "d"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("state 命令携带 layoutIds → 拒绝")
    void stateCommandWithLayoutIdsRejected() {
        assertThatThrownBy(() -> command(0, SURVEY_ID, "submitted", List.of("pixiv-batch-landscape")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command(0, SURVEY_ID, "snooze", List.of("pixiv-batch-landscape")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command(0, SURVEY_ID, "never", List.of("pixiv-batch-landscape")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /* ============================================================
       I. 原子写
    ============================================================ */

    @Test
    @DisplayName("成功写入后正式文件可严格解析且与内存快照一致")
    void persistedFileMatchesSnapshot() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(submitted(0), NOW);
        store.apply(recordSeen(1, "pixiv-batch-landscape"), NOW);

        String persisted = Files.readString(stateFile(), StandardCharsets.UTF_8);
        assertThat(persisted).contains("\"schemaVersion\":1").contains("\"revision\":2");
        LayoutFeedbackStateSnapshot reloaded = new LayoutFeedbackStateStore(stateFile()).snapshot();
        assertThat(reloaded).isEqualTo(store.snapshot());
    }

    @Test
    @DisplayName("写入失败时旧快照保留且临时文件被清理")
    void failedWriteKeepsOldSnapshotAndCleansTemp() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(submitted(0), NOW);
        // 让状态目录变成普通文件：createDirectories 必然抛 IOException（跨平台确定）。
        // 注意用 record_seen（必然产生变化并触发持久化；never 对 submitted 是 no-op 不写盘）。
        Path parent = stateFile().getParent();
        Files.delete(stateFile());
        Files.delete(parent);
        Files.writeString(parent, "not a directory", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> store.apply(recordSeen(1, "pixiv-batch-landscape"), NOW))
                .isInstanceOf(IOException.class);

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.state().status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
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

        assertThatThrownBy(() -> store.apply(submitted(0), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("并发写在同一 JVM 内串行，CAS 重试收敛")
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
                    long revision = 0;
                    boolean applied = false;
                    while (!applied) {
                        LayoutFeedbackStateStore.ApplyResult result =
                                store.apply(recordSeen(revision, layout), NOW);
                        if (result.status() == LayoutFeedbackStateStore.ApplyStatus.CONFLICT) {
                            conflicts.incrementAndGet();
                            revision = result.snapshot().revision();
                        } else {
                            applied = true;
                            revision = result.snapshot().revision();
                        }
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

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.seen().keySet()).containsExactlyInAnyOrder(layouts);
        assertThat(snapshot.revision()).isEqualTo(3);
        assertThat(conflicts.get()).isGreaterThan(0);
    }

    private java.util.List<Path> corruptFiles() throws IOException {
        try (var stream = Files.list(stateFile().getParent())) {
            return stream.filter(path -> path.getFileName().toString().contains(".corrupt-")).toList();
        }
    }

    private java.util.List<Path> corruptFilesOf(Path file) throws IOException {
        if (!Files.isDirectory(file.getParent())) {
            return java.util.List.of();
        }
        try (var stream = Files.list(file.getParent())) {
            return stream.filter(path -> path.getFileName().toString().contains(".corrupt-")).toList();
        }
    }
}
