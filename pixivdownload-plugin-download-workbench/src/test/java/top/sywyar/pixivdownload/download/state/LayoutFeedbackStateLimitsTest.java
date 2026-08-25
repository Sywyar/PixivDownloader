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

@DisplayName("布局调查状态容量与修订边界")
class LayoutFeedbackStateLimitsTest extends LayoutFeedbackStateStoreTestSupport {
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

}
