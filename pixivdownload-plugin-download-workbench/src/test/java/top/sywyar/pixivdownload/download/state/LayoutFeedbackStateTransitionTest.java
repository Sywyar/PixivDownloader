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

@DisplayName("布局调查状态迁移规则")
class LayoutFeedbackStateTransitionTest extends LayoutFeedbackStateStoreTestSupport {
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

}
