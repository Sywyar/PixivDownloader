package top.sywyar.pixivdownload.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("计划任务本轮运行队列 ScheduleRunQueue")
class ScheduleRunQueueTest {

    private static final ScheduleCapabilityOwner WORK_OWNER =
            new ScheduleCapabilityOwner("third-party", "third-party", 1L);
    private static final long WORK_PUBLICATION_ID = 7L;

    @Nested
    @DisplayName("登记与查询")
    class RegistryLifecycle {

        @Test
        @DisplayName("begin 整体替换上一轮队列；get 返回最近一轮；remove 清除")
        void beginReplacesAndRemoveClears() {
            ScheduleRunQueue queue = new ScheduleRunQueue();
            ScheduleRunQueue.Run first = queue.begin(1L);
            discover(first, work("third-party.photo", "100"));
            assertThat(queue.get(1L)).isSameAs(first);

            ScheduleRunQueue.Run second = queue.begin(1L);
            assertThat(queue.get(1L)).isSameAs(second);
            assertThat(second.snapshot()).isEmpty();

            queue.remove(1L);
            assertThat(queue.get(1L)).isNull();
        }

        @Test
        @DisplayName("从未运行的任务 get 返回 null")
        void getReturnsNullWhenNeverRun() {
            assertThat(new ScheduleRunQueue().get(42L)).isNull();
        }
    }

    @Nested
    @DisplayName("条目记录")
    class ItemRecording {

        @Test
        @DisplayName("discovered 保留发现顺序、按作品类型与 ID 去重幂等")
        void discoveredKeepsOrderAndDedupes() {
            ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun();
            discover(run, work("third-party.photo", "100"));
            discover(run, work("third-party.photo", "99"));
            discover(run, work("third-party.photo", "100")); // 重复
            discover(run, null); // 忽略

            List<ScheduleRunQueue.Item> items = run.snapshot();
            assertThat(items)
                    .extracting(item -> item.key().id())
                    .containsExactly("100", "99");
            assertThat(items).allSatisfy(it ->
                    assertThat(it.status()).isEqualTo(ScheduleRunQueue.STATUS_PENDING));
            assertThat(items.get(0).key().workType()).isEqualTo("third-party.photo");
        }

        @Test
        @DisplayName("相同 ID 的不同第三方作品类型分别登记并按复合身份更新")
        void sameIdAcrossWorkTypesRemainsDistinct() {
            ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun();
            ScheduledWork photo = work(
                    "third-party.photo", "100",
                    new ScheduledWorkPresentation(
                            "图片标题", "作者甲", "thumb-a", Map.of("rating", "adult")));
            ScheduledWork text = work(
                    "third-party.text", "100",
                    new ScheduledWorkPresentation(
                            "文本标题", "作者乙", "thumb-b", Map.of("language", "zh")));
            discover(run, photo);
            discover(run, text);
            discover(run, text);

            run.mark(photo.key(),
                    ScheduleRunQueue.STATUS_SKIPPED_FILTER, null);
            ScheduledWorkResult result = new ScheduledWorkResult(
                    ScheduledWorkResult.Outcome.COMPLETED,
                    "third-party.completed",
                    Map.of("privatePhase", "indexing"),
                    true);
            run.markResult(
                    text.key(), result,
                    ScheduleRunQueue.STATUS_DOWNLOADED, result.resultCode());

            assertThat(run.snapshot())
                    .extracting(item -> item.key().workType(),
                            item -> item.presentation().title(),
                            ScheduleRunQueue.Item::status,
                            ScheduleRunQueue.Item::result)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(
                                    "third-party.photo", "图片标题",
                                    ScheduleRunQueue.STATUS_SKIPPED_FILTER, null),
                            org.assertj.core.groups.Tuple.tuple(
                                    "third-party.text", "文本标题",
                                    ScheduleRunQueue.STATUS_DOWNLOADED, result));
        }

        @Test
        @DisplayName("mark 与 markResult 仅作用于已登记的复合作品身份")
        void markAndResultOnlyAffectKnownKeys() {
            ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun();
            ScheduledWork known = work("third-party.text", "100");
            discover(run, known);
            run.mark(
                    known.key(), ScheduleRunQueue.STATUS_DOWNLOADED, null);
            run.markResult(
                    new ScheduledWorkKey("third-party.text", "404"),
                    ScheduledWorkResult.completed(),
                    ScheduleRunQueue.STATUS_DOWNLOADED,
                    "work.completed");

            List<ScheduleRunQueue.Item> items = run.snapshot();
            assertThat(items).hasSize(1);
            ScheduleRunQueue.Item it = items.get(0);
            assertThat(it.result()).isNull();
            assertThat(it.status()).isEqualTo(ScheduleRunQueue.STATUS_DOWNLOADED);
        }

        @Test
        @DisplayName("snapshot 返回拷贝：后续写入不影响已取出的快照")
        void snapshotIsIndependentCopy() {
            ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun();
            ScheduledWork first = work("third-party.photo", "100");
            discover(run, first);
            List<ScheduleRunQueue.Item> before = run.snapshot();

            run.mark(first.key(), ScheduleRunQueue.STATUS_FAILED, "later");
            discover(run, work("third-party.photo", "99"));

            assertThat(before).hasSize(1);
            assertThat(before.get(0).status()).isEqualTo(ScheduleRunQueue.STATUS_PENDING);
            assertThatThrownBy(before::clear)
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("上限保护")
    class Truncation {

        @Test
        @DisplayName("超过 MAX_ITEMS 后不再记录条目并置 truncated")
        void marksTruncatedBeyondCap() {
            ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun();
            for (int i = 0; i < ScheduleRunQueue.MAX_ITEMS + 5; i++) {
                discover(run, work("third-party.photo", String.valueOf(i)));
            }
            assertThat(run.truncated()).isTrue();
            assertThat(run.snapshot()).hasSize(ScheduleRunQueue.MAX_ITEMS);
        }

        @Test
        @DisplayName("接近字段上限的展示投影受单轮聚合字节预算约束")
        void aggregatePresentationBudgetBoundsRetainedQueue() {
            ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun();
            ScheduledWorkPresentation presentation = maximumPresentation();

            for (int index = 0; index < 100; index++) {
                discover(run, work(
                        "third-party.large-presentation",
                        "work-" + index,
                        presentation));
            }

            assertThat(run.truncated()).isTrue();
            assertThat(run.snapshot()).hasSizeLessThan(100);
            assertThat(run.retainedUtf8Bytes())
                    .isLessThanOrEqualTo(ScheduleRunQueue.MAX_RETAINED_UTF8_BYTES);
        }

        @Test
        @DisplayName("结果属性使队列突破聚合预算时丢弃该展示条目并保持执行结果不受影响")
        void resultGrowthDropsProjectionBeyondAggregateBudget() {
            ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun();
            int index = 0;
            while (!run.truncated() && index < ScheduleRunQueue.MAX_ITEMS) {
                String id = String.format("%04d", index) + "x".repeat(496);
                discover(run, work("third-party.large-result", id));
                index++;
            }
            List<ScheduleRunQueue.Item> before = run.snapshot();
            ScheduledWorkKey firstKey = before.get(0).key();
            ScheduledWorkResult largeResult = new ScheduledWorkResult(
                    ScheduledWorkResult.Outcome.COMPLETED,
                    "third-party.completed",
                    Map.of("blob", "X".repeat(4_096)));

            run.markResult(
                    firstKey,
                    largeResult,
                    ScheduleRunQueue.STATUS_DOWNLOADED,
                    largeResult.resultCode());

            assertThat(run.truncated()).isTrue();
            assertThat(run.snapshot())
                    .hasSize(before.size() - 1)
                    .noneSatisfy(item -> assertThat(item.key()).isEqualTo(firstKey));
            assertThat(run.retainedUtf8Bytes())
                    .isLessThanOrEqualTo(ScheduleRunQueue.MAX_RETAINED_UTF8_BYTES);
        }

        @Test
        @DisplayName("未超上限时 truncated 为 false")
        void notTruncatedWithinCap() {
            ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun();
            discover(run, work("third-party.photo", "1"));
            discover(run, work("third-party.photo", "2"));
            assertThat(run.truncated()).isFalse();
        }
    }

    private static ScheduledWork work(String workType, String id) {
        return work(workType, id, ScheduledWorkPresentation.empty());
    }

    private static void discover(ScheduleRunQueue.Run run, ScheduledWork work) {
        run.discovered(work, WORK_OWNER, WORK_PUBLICATION_ID);
    }

    private static ScheduledWork work(
            String workType,
            String id,
            ScheduledWorkPresentation presentation) {
        return new ScheduledWork(
                new ScheduledWorkKey(workType, id),
                "fixture.work",
                1,
                "{}",
                presentation,
                List.of());
    }

    private static ScheduledWorkPresentation maximumPresentation() {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int index = 0; index < 8; index++) {
            attributes.put("field" + index, "A".repeat(4_088));
        }
        return new ScheduledWorkPresentation(
                "T".repeat(ScheduledWorkPresentation.MAX_TITLE_BYTES),
                "A".repeat(ScheduledWorkPresentation.MAX_AUTHOR_BYTES),
                "R".repeat(ScheduledWorkPresentation.MAX_THUMBNAIL_REFERENCE_BYTES),
                attributes);
    }
}
