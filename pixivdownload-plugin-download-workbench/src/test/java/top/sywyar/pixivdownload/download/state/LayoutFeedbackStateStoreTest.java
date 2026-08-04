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

    private LayoutFeedbackCommandRequest command(String surveyId, String command, List<String> layoutIds) {
        return new LayoutFeedbackCommandRequest(surveyId, command, layoutIds);
    }

    private LayoutFeedbackCommandRequest recordSeen(String... layoutIds) {
        return command(SURVEY_ID, "record_seen", List.of(layoutIds));
    }

    private LayoutFeedbackCommandRequest submitted() {
        return command(SURVEY_ID, "submitted", null);
    }

    private LayoutFeedbackCommandRequest never() {
        return command(SURVEY_ID, "never", null);
    }

    private LayoutFeedbackCommandRequest snooze() {
        return command(SURVEY_ID, "snooze", null);
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

    /* ============================================================
       无 CAS 命令 / revision
    ============================================================ */

    @Test
    @DisplayName("命令成功并递增 revision；重复 submitted 幂等 no-op 保持 revision")
    void appliesWithoutCasAndIncrementsRevisionOnlyOnChange() throws IOException {
        LayoutFeedbackStateStore store = store();

        LayoutFeedbackStateStore.ApplyResult first = store.apply(submitted(), NOW);
        assertThat(first.changed()).isTrue();
        assertThat(first.snapshot().revision()).isEqualTo(1);
        assertThat(store.snapshot().revision()).isEqualTo(1);

        LayoutFeedbackStateStore.ApplyResult noop = store.apply(submitted(), NOW + 5000);
        assertThat(noop.changed()).as("重复 submitted 幂等 no-op").isFalse();
        assertThat(noop.snapshot().revision()).as("no-op 保持 revision").isEqualTo(1);
        assertThat(store.snapshot().revision()).isEqualTo(1);

        LayoutFeedbackStateStore.ApplyResult seen = store.apply(recordSeen("pixiv-batch-landscape"), NOW);
        assertThat(seen.changed()).isTrue();
        assertThat(seen.snapshot().revision()).isEqualTo(2);
    }

    @Test
    @DisplayName("no-op 不落盘：文件字节不变")
    void noOpDoesNotRewriteFile() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(submitted(), NOW);
        String before = Files.readString(stateFile(), StandardCharsets.UTF_8);

        store.apply(submitted(), NOW + 5000);

        assertThat(Files.readString(stateFile(), StandardCharsets.UTF_8))
                .as("no-op 不重写状态文件")
                .isEqualTo(before);
    }

    /* ============================================================
       按 Survey ID 隔离
    ============================================================ */

    @Test
    @DisplayName("两个 Survey 状态独立：Survey A submitted 不影响 Survey B")
    void surveyStatesAreIndependent() throws IOException {
        LayoutFeedbackStateStore store = store();

        store.apply(submitted(), NOW);
        store.apply(command(OTHER_SURVEY_ID, "never", null), NOW + 1000);

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(snapshot.state(OTHER_SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.NEVER);
        assertThat(snapshot.states()).hasSize(2);
    }

    @Test
    @DisplayName("旧 Survey A 命令不能覆盖新 Survey B 状态")
    void oldSurveyCommandDoesNotOverwriteNewSurvey() throws IOException {
        LayoutFeedbackStateStore store = store();

        store.apply(command(OTHER_SURVEY_ID, "submitted", null), NOW);
        store.apply(snooze(), NOW);

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.state(OTHER_SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(snapshot.state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.SNOOZED);
    }

    @Test
    @DisplayName("record_seen 不修改任何 Survey decision，seen 全局共享")
    void recordSeenNeverTouchesAnyState() throws IOException {
        LayoutFeedbackStateStore store = store();

        store.apply(recordSeen("pixiv-batch-landscape"), NOW);

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.states()).isEmpty();
        assertThat(snapshot.seen().keySet()).containsExactly("pixiv-batch-landscape");
    }

    /* ============================================================
       状态单调性
    ============================================================ */

    @Test
    @DisplayName("submitted 不被 never / snooze 降级")
    void submittedNotDowngraded() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(submitted(), NOW);

        store.apply(never(), NOW + 1000);
        store.apply(snooze(), NOW + 1000);

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(snapshot.revision()).isEqualTo(1);
    }

    @Test
    @DisplayName("never 可被 submitted 升级；never 不被 snooze 降级")
    void neverUpgradedBySubmittedNotDowngradedBySnooze() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(never(), NOW);

        store.apply(snooze(), NOW + 1000);
        assertThat(store.snapshot().state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.NEVER);

        store.apply(submitted(), NOW + 1000);
        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(snapshot.revision()).isEqualTo(2);
    }

    @Test
    @DisplayName("snooze 可被 never / submitted 升级")
    void snoozeUpgradedByNeverAndSubmitted() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(snooze(), NOW);

        store.apply(never(), NOW + 1000);
        assertThat(store.snapshot().state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.NEVER);

        store.apply(submitted(), NOW + 1000);
        assertThat(store.snapshot().state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
    }

    @Test
    @DisplayName("重复 snooze 使用新的服务端时间，snoozedUntil 单调不倒退")
    void repeatedSnoozeRefreshesServerTime() throws IOException {
        LayoutFeedbackStateStore store = store();
        LayoutFeedbackStateStore.ApplyResult first = store.apply(snooze(), NOW);
        LayoutFeedbackStateStore.ApplyResult second = store.apply(snooze(), NOW + 1000);

        assertThat(first.snapshot().state(SURVEY_ID).snoozedUntil())
                .isEqualTo(NOW + LayoutFeedbackStateStore.SNOOZE_MILLIS);
        assertThat(second.snapshot().state(SURVEY_ID).snoozedUntil())
                .isEqualTo(NOW + 1000 + LayoutFeedbackStateStore.SNOOZE_MILLIS);
        assertThat(second.snapshot().revision()).isEqualTo(2);
    }

    /* ============================================================
       服务端墙钟回拨防御
    ============================================================ */

    @Test
    @DisplayName("重复 snooze，第二次 now 比第一次早 1 小时：snoozedUntil 不缩短、updatedAt 不倒退、no-op 不落盘")
    void repeatedSnoozeWithRollbackDoesNotShorten() throws IOException {
        LayoutFeedbackStateStore store = store();
        LayoutFeedbackStateStore.ApplyResult first = store.apply(snooze(), NOW);
        String before = Files.readString(stateFile(), StandardCharsets.UTF_8);

        LayoutFeedbackStateStore.ApplyResult second = store.apply(snooze(), NOW - 60 * 60 * 1000);

        assertThat(second.changed()).as("墙钟回拨后重复 snooze 是 no-op").isFalse();
        assertThat(second.snapshot().state(SURVEY_ID).snoozedUntil())
                .as("墙钟回拨不得缩短已有 snooze")
                .isEqualTo(NOW + LayoutFeedbackStateStore.SNOOZE_MILLIS);
        assertThat(second.snapshot().state(SURVEY_ID).updatedAt())
                .as("updatedAt 不得倒退")
                .isEqualTo(NOW);
        assertThat(second.snapshot().revision()).isEqualTo(1);
        assertThat(Files.readString(stateFile(), StandardCharsets.UTF_8))
                .as("no-op 不重写状态文件")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("重复 snooze，回拨后 proposedUntil 仍小于旧值：no-op、revision 不变、文件字节不变")
    void repeatedSnoozeRollbackIsNoOpAndDoesNotRewrite() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(snooze(), NOW);
        String before = Files.readString(stateFile(), StandardCharsets.UTF_8);
        long revisionBefore = store.snapshot().revision();

        LayoutFeedbackStateStore.ApplyResult result = store.apply(snooze(), NOW - 60 * 60 * 1000);

        assertThat(result.changed()).as("墙钟回拨后重复 snooze 是 no-op").isFalse();
        assertThat(store.snapshot().revision()).as("no-op 保持 revision").isEqualTo(revisionBefore);
        String after = Files.readString(stateFile(), StandardCharsets.UTF_8);
        assertThat(after).as("no-op 不重写状态文件").isEqualTo(before);
        assertThat(corruptFiles()).isEmpty();
    }

    @Test
    @DisplayName("never → submitted，now 小于 old.updatedAt：submitted 生效且 updatedAt 不倒退")
    void upgradeKeepsUpdatedAtMonotonicUnderRollback() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(never(), NOW);

        LayoutFeedbackStateStore.ApplyResult result =
                store.apply(submitted(), NOW - 60 * 60 * 1000);

        assertThat(result.snapshot().state(SURVEY_ID).status()).isEqualTo(LayoutFeedbackDecision.SUBMITTED);
        assertThat(result.snapshot().state(SURVEY_ID).updatedAt())
                .as("升级后 updatedAt 保持旧值或更大")
                .isEqualTo(NOW);
        assertThat(result.snapshot().revision()).isEqualTo(2);
    }

    @Test
    @DisplayName("record_seen：旧 first=100 / last=200，新 now=50：first/last 保持、no-op、revision 不变")
    void recordSeenDoesNotMoveBackwards() throws IOException {
        LayoutFeedbackStateStore store = store();
        store.apply(recordSeen("pixiv-batch-landscape"), 100);
        store.apply(recordSeen("pixiv-batch-landscape"), 200);
        String before = Files.readString(stateFile(), StandardCharsets.UTF_8);

        LayoutFeedbackStateStore.ApplyResult result = store.apply(recordSeen("pixiv-batch-landscape"), 50);

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
        store.apply(recordSeen("pixiv-batch-landscape"), 100);
        store.apply(recordSeen("pixiv-batch-landscape"), 200);

        LayoutFeedbackStateStore.ApplyResult result = store.apply(recordSeen("pixiv-batch-landscape"), 300);

        LayoutFeedbackSeenEntry entry = result.snapshot().seen().get("pixiv-batch-landscape");
        assertThat(entry.firstSeenAt()).isEqualTo(100);
        assertThat(entry.lastSeenAt()).isEqualTo(300);
        assertThat(result.snapshot().revision()).isEqualTo(3);
    }

    @Test
    @DisplayName("now 为负数：新 entry / 新状态使用 0，不生成非法时间")
    void negativeNowClampedToZero() throws IOException {
        LayoutFeedbackStateStore store = store();

        LayoutFeedbackStateStore.ApplyResult seen = store.apply(recordSeen("pixiv-batch-landscape"), -500);

        LayoutFeedbackSeenEntry entry = seen.snapshot().seen().get("pixiv-batch-landscape");
        assertThat(entry.firstSeenAt()).isZero();
        assertThat(entry.lastSeenAt()).isZero();
        assertThat(entry.lastSeenAt()).as("lastSeenAt 不得小于 firstSeenAt")
                .isGreaterThanOrEqualTo(entry.firstSeenAt());

        LayoutFeedbackStateStore.ApplyResult state = store.apply(snooze(), -500);
        assertThat(state.snapshot().state(SURVEY_ID).updatedAt()).isZero();
        assertThat(state.snapshot().state(SURVEY_ID).snoozedUntil())
                .isEqualTo(LayoutFeedbackStateStore.SNOOZE_MILLIS);
    }

    @Test
    @DisplayName("saturatingAdd 语义：near Long.MAX_VALUE 不溢出为负数")
    void saturatingAddNearMaxValueDoesNotOverflow() throws IOException {
        long nearMax = Long.MAX_VALUE - 1000;
        LayoutFeedbackStateStore store = store();

        LayoutFeedbackStateStore.ApplyResult result = store.apply(snooze(), nearMax);

        assertThat(result.changed()).isTrue();
        assertThat(result.snapshot().state(SURVEY_ID).updatedAt()).isEqualTo(nearMax);
        assertThat(result.snapshot().state(SURVEY_ID).snoozedUntil())
                .as("接近 Long.MAX_VALUE 时饱和为 Long.MAX_VALUE，不得溢出为负数")
                .isEqualTo(Long.MAX_VALUE);
    }

    /* ============================================================
       states 上限与淘汰
    ============================================================ */

    @Test
    @DisplayName("states 数量上限 32：第 33 个按 updatedAt 淘汰最旧状态")
    void evictsOldestStateAtCapacity() throws IOException {
        LayoutFeedbackStateStore store = store();
        String[] surveyIds = new String[LayoutFeedbackStateStore.MAX_SURVEY_STATES + 2];
        for (int i = 0; i < surveyIds.length; i++) {
            surveyIds[i] = String.format("aaaaaaaa-bbbb-cccc-dddd-%012d", i);
        }
        for (int i = 0; i < surveyIds.length; i++) {
            store.apply(command(surveyIds[i], "submitted", null), NOW + i * 1000L);
        }

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.states()).hasSize(LayoutFeedbackStateStore.MAX_SURVEY_STATES);
        // 最旧的两个（updatedAt 最小）被淘汰；较新的保留。
        assertThat(snapshot.state(surveyIds[0])).isNull();
        assertThat(snapshot.state(surveyIds[1])).isNull();
        assertThat(snapshot.state(surveyIds[2])).isNotNull();
        assertThat(snapshot.state(surveyIds[surveyIds.length - 1])).isNotNull();
    }

    @Test
    @DisplayName("淘汰顺序确定：第 33 个状态触发淘汰，updatedAt 相同时按 Survey ID 字典序")
    void evictionOrderDeterministicOnTie() throws IOException {
        LayoutFeedbackStateStore store = store();
        // 32 个状态，全部同一 updatedAt；再加第 33 个（同 updatedAt）触发淘汰。
        String[] surveyIds = new String[LayoutFeedbackStateStore.MAX_SURVEY_STATES + 1];
        for (int i = 0; i < surveyIds.length; i++) {
            surveyIds[i] = String.format("aaaaaaaa-bbbb-cccc-dddd-%012d", i);
            store.apply(command(surveyIds[i], "submitted", null), NOW);
        }

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.states()).hasSize(LayoutFeedbackStateStore.MAX_SURVEY_STATES);
        // updatedAt 相同时按 Survey ID 字典序淘汰最小者：'...-000000000000' 字典序最小。
        assertThat(snapshot.state(surveyIds[0])).as("updatedAt 相同时按 Survey ID 淘汰最小者").isNull();
        assertThat(snapshot.state(surveyIds[surveyIds.length - 1])).isNotNull();
    }

    @Test
    @DisplayName("淘汰不淘汰本次正在写入的 Survey")
    void evictionKeepsCurrentSurvey() throws IOException {
        LayoutFeedbackStateStore store = store();
        String[] surveyIds = new String[LayoutFeedbackStateStore.MAX_SURVEY_STATES];
        for (int i = 0; i < surveyIds.length; i++) {
            surveyIds[i] = String.format("aaaaaaaa-bbbb-cccc-dddd-%012d", i);
            store.apply(command(surveyIds[i], "submitted", null), NOW + i * 1000L);
        }
        // 新 Survey 写入触发淘汰：本次写入的 Survey 必须保留。
        String newest = "aaaaaaaa-bbbb-cccc-dddd-ffffffffffff";
        store.apply(command(newest, "submitted", null), NOW + 1_000_000L);

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.states()).hasSize(LayoutFeedbackStateStore.MAX_SURVEY_STATES);
        assertThat(snapshot.state(newest)).isNotNull();
    }

    /* ============================================================
       states 数量上限贯穿加载（schemaVersion=2 文件）
    ============================================================ */

    private String stateJson(String surveyId, long updatedAt) {
        return "{\"surveyId\":\"" + surveyId
                + "\",\"status\":\"submitted\",\"updatedAt\":" + updatedAt + ",\"snoozedUntil\":0}";
    }

    private void writeV2Document(long revision, int stateCount) throws IOException {
        StringBuilder json = new StringBuilder("{\"schemaVersion\":2,\"revision\":" + revision + ",\"states\":{");
        for (int i = 0; i < stateCount; i++) {
            if (i > 0) {
                json.append(",");
            }
            String surveyId = String.format("aaaaaaaa-bbbb-cccc-dddd-%012d", i);
            json.append("\"").append(surveyId).append("\":").append(stateJson(surveyId, i + 1L));
        }
        json.append("},\"seen\":{}}");
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(), json.toString(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("schemaVersion=2 恰好 32 个 states：正常加载，不隔离")
    void v2FileWithExactlyMaxStatesLoads() throws IOException {
        writeV2Document(7L, LayoutFeedbackStateStore.MAX_SURVEY_STATES);

        LayoutFeedbackStateStore store = store();

        assertThat(store.degraded()).isFalse();
        assertThat(corruptFiles()).as("恰好 32 项不得被隔离").isEmpty();
        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.states()).hasSize(LayoutFeedbackStateStore.MAX_SURVEY_STATES);
        assertThat(snapshot.revision()).isEqualTo(7L);
    }

    @Test
    @DisplayName("schemaVersion=2 超过 32 个 states：按损坏文件隔离，Store 使用空快照并继续可用")
    void v2FileOverLimitQuarantinedAndStoreUsable() throws IOException {
        writeV2Document(7L, LayoutFeedbackStateStore.MAX_SURVEY_STATES + 1);

        LayoutFeedbackStateStore store = store();

        assertThat(store.degraded()).isFalse();
        assertThat(corruptFiles()).as("超限 v2 文件必须按损坏隔离").hasSize(1);
        assertThat(Files.exists(stateFile())).as("超限文件必须被移走").isFalse();
        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.states()).as("Store 使用空快照").isEmpty();
        assertThat(snapshot.revision()).isZero();
        // 超限文件不得因后续 record_seen 绕过限制：Store 继续可用，正常淘汰仍生效。
        store.apply(recordSeen("pixiv-batch-landscape"), NOW);
        assertThat(store.snapshot().seen()).containsKey("pixiv-batch-landscape");
    }

    @Test
    @DisplayName("LayoutFeedbackStateSnapshot 直接构造 33 个 states 拒绝")
    void snapshotRejectsOverLimitStates() {
        Map<String, LayoutFeedbackStateEntry> states = new java.util.LinkedHashMap<>();
        for (int i = 0; i < LayoutFeedbackStateStore.MAX_SURVEY_STATES + 1; i++) {
            String surveyId = String.format("aaaaaaaa-bbbb-cccc-dddd-%012d", i);
            states.put(surveyId, new LayoutFeedbackStateEntry(
                    surveyId, LayoutFeedbackDecision.SUBMITTED, i + 1L, 0L));
        }

        assertThatThrownBy(() -> new LayoutFeedbackStateSnapshot(0L, states, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("LayoutFeedbackStateDocument 构造 33 个 states 拒绝（schemaVersion=2）")
    void documentRejectsOverLimitStates() {
        Map<String, LayoutFeedbackStateEntry> states = new java.util.LinkedHashMap<>();
        for (int i = 0; i < LayoutFeedbackStateStore.MAX_SURVEY_STATES + 1; i++) {
            String surveyId = String.format("aaaaaaaa-bbbb-cccc-dddd-%012d", i);
            states.put(surveyId, new LayoutFeedbackStateEntry(
                    surveyId, LayoutFeedbackDecision.SUBMITTED, i + 1L, 0L));
        }

        assertThatThrownBy(() -> new LayoutFeedbackStateDocument(2, 0L, null, states, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /* ============================================================
       revision 安全边界（Number.MAX_SAFE_INTEGER）
    ============================================================ */

    @Test
    @DisplayName("revision=MAX_SAFE_REVISION-1：一次真实修改成功，revision 变为 MAX_SAFE_REVISION")
    void revisionAdvancesToMaxFromOneBelow() throws IOException {
        writeV2Document(LayoutFeedbackStateStore.MAX_SAFE_REVISION - 1L, 0);
        LayoutFeedbackStateStore store = store();

        LayoutFeedbackStateStore.ApplyResult result = store.apply(submitted(), NOW);

        assertThat(result.changed()).isTrue();
        assertThat(result.snapshot().revision()).isEqualTo(LayoutFeedbackStateStore.MAX_SAFE_REVISION);
        assertThat(store.snapshot().revision()).isEqualTo(LayoutFeedbackStateStore.MAX_SAFE_REVISION);
    }

    @Test
    @DisplayName("revision==MAX_SAFE_REVISION：重复 submitted 等幂等 no-op 成功，revision 保持、不落盘")
    void revisionExhaustedNoOpStillSucceeds() throws IOException {
        writeV2Document(LayoutFeedbackStateStore.MAX_SAFE_REVISION - 1L, 0);
        LayoutFeedbackStateStore store = store();
        // 先构造真实 submitted 状态（真实修改使 revision 到达上限）。
        LayoutFeedbackStateStore.ApplyResult first = store.apply(submitted(), NOW);
        assertThat(first.changed()).as("测试前置：首次写入推进到上限").isTrue();
        assertThat(first.snapshot().revision()).isEqualTo(LayoutFeedbackStateStore.MAX_SAFE_REVISION);
        String before = Files.readString(stateFile(), StandardCharsets.UTF_8);

        LayoutFeedbackStateStore.ApplyResult noop = store.apply(submitted(), NOW + 5000);

        assertThat(noop.changed()).as("幂等 no-op 不受 revision 耗尽影响").isFalse();
        assertThat(noop.snapshot().revision()).isEqualTo(LayoutFeedbackStateStore.MAX_SAFE_REVISION);
        assertThat(store.snapshot().revision()).isEqualTo(LayoutFeedbackStateStore.MAX_SAFE_REVISION);
        assertThat(Files.readString(stateFile(), StandardCharsets.UTF_8))
                .as("no-op 不重写状态文件")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("revision==MAX_SAFE_REVISION：record_seen 新布局等真实修改失败，revision 不变、文件不变、不产生负数")
    void revisionExhaustedRealChangeFailsWithoutSideEffects() throws IOException {
        writeV2Document(LayoutFeedbackStateStore.MAX_SAFE_REVISION, 0);
        LayoutFeedbackStateStore store = store();
        String before = Files.readString(stateFile(), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> store.apply(recordSeen("pixiv-batch-landscape"), NOW))
                .isInstanceOf(LayoutFeedbackRevisionExhaustedException.class);

        LayoutFeedbackStateSnapshot snapshot = store.snapshot();
        assertThat(snapshot.revision()).as("内存快照 revision 不变").isEqualTo(LayoutFeedbackStateStore.MAX_SAFE_REVISION);
        assertThat(snapshot.seen()).as("内存快照 seen 不变").isEmpty();
        assertThat(snapshot.states()).as("内存快照 states 不变").isEmpty();
        assertThat(Files.readString(stateFile(), StandardCharsets.UTF_8))
                .as("状态文件不变")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("revision 超过 MAX_SAFE_REVISION 的文件：按损坏文件隔离，空快照")
    void revisionOverMaxFileQuarantined() throws IOException {
        writeV2Document(LayoutFeedbackStateStore.MAX_SAFE_REVISION + 1L, 1);

        LayoutFeedbackStateStore store = store();

        assertThat(store.degraded()).isFalse();
        assertThat(corruptFiles()).as("超限 revision 文件必须按损坏隔离").hasSize(1);
        assertThat(store.snapshot().revision()).isZero();
        assertThat(store.snapshot().states()).isEmpty();
    }

    @Test
    @DisplayName("revision 为负的文件：按损坏文件隔离")
    void negativeRevisionFileQuarantined() throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(),
                "{\"schemaVersion\":2,\"revision\":-1,\"states\":{},\"seen\":{}}",
                StandardCharsets.UTF_8);

        LayoutFeedbackStateStore store = store();

        // 先触发懒加载（ensureLoaded），隔离在首次访问时执行。
        assertThat(store.snapshot().revision()).isZero();
        assertThat(corruptFiles()).as("负 revision 文件必须按损坏隔离").hasSize(1);
    }

    /* ============================================================
       输入校验（命令构造）
    ============================================================ */

    @Test
    @DisplayName("unknown command → 拒绝")
    void unknownCommandRejected() {
        assertThatThrownBy(() -> command(SURVEY_ID, "explode", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("缺 surveyId / 非法 surveyId → 拒绝")
    void invalidSurveyIdRejected() {
        assertThatThrownBy(() -> new LayoutFeedbackCommandRequest(null, "never", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LayoutFeedbackCommandRequest("bad id", "never", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("record_seen 无 layoutIds → 拒绝")
    void recordSeenWithoutLayoutIdsRejected() {
        assertThatThrownBy(() -> command(SURVEY_ID, "record_seen", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command(SURVEY_ID, "record_seen", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("record_seen 未知布局 / 重复布局 → 拒绝")
    void recordSeenInvalidLayoutsRejected() {
        assertThatThrownBy(() -> recordSeen("pixiv-batch-nope"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recordSeen("pixiv-batch-landscape", "pixiv-batch-landscape"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recordSeen("a", "b", "c", "d"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("state 命令携带 layoutIds → 拒绝")
    void stateCommandWithLayoutIdsRejected() {
        assertThatThrownBy(() -> command(SURVEY_ID, "submitted", List.of("pixiv-batch-landscape")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command(SURVEY_ID, "snooze", List.of("pixiv-batch-landscape")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command(SURVEY_ID, "never", List.of("pixiv-batch-landscape")))
                .isInstanceOf(IllegalArgumentException.class);
    }

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
