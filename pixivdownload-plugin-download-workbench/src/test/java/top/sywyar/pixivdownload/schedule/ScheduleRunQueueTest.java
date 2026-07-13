package top.sywyar.pixivdownload.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("计划任务本轮运行队列 ScheduleRunQueue")
class ScheduleRunQueueTest {

    @Nested
    @DisplayName("登记与查询")
    class RegistryLifecycle {

        @Test
        @DisplayName("begin 整体替换上一轮队列；get 返回最近一轮；remove 清除")
        void beginReplacesAndRemoveClears() {
            ScheduleRunQueue queue = new ScheduleRunQueue();
            ScheduleRunQueue.Run first = queue.begin(1L, ScheduleRunQueue.KIND_ILLUST);
            first.discovered("100");
            assertThat(queue.get(1L)).isSameAs(first);

            ScheduleRunQueue.Run second = queue.begin(1L, ScheduleRunQueue.KIND_ILLUST);
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
            ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST);
            run.discovered("100");
            run.discovered("99");
            run.discovered("100"); // 重复
            run.discovered(null);  // 忽略

            List<ScheduleRunQueue.Item> items = run.snapshot();
            assertThat(items).extracting(ScheduleRunQueue.Item::getId).containsExactly("100", "99");
            assertThat(items).allSatisfy(it ->
                    assertThat(it.getStatus()).isEqualTo(ScheduleRunQueue.STATUS_PENDING));
            assertThat(items.get(0).getKind()).isEqualTo(ScheduleRunQueue.KIND_ILLUST);
        }

        @Test
        @DisplayName("相同 ID 的插画与小说分别登记并按复合身份更新")
        void sameIdAcrossWorkTypesRemainsDistinct() {
            ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST);
            run.discovered("100", ScheduleRunQueue.KIND_ILLUST);
            run.discovered("100", ScheduleRunQueue.KIND_NOVEL);
            run.discovered("100", ScheduleRunQueue.KIND_NOVEL);

            run.setMeta("100", ScheduleRunQueue.KIND_ILLUST, "插画标题", 1, false);
            run.setMeta("100", ScheduleRunQueue.KIND_NOVEL, "小说标题", 0, true);
            run.mark("100", ScheduleRunQueue.KIND_ILLUST,
                    ScheduleRunQueue.STATUS_SKIPPED_FILTER, null);
            run.mark("100", ScheduleRunQueue.KIND_NOVEL,
                    ScheduleRunQueue.STATUS_DOWNLOADED, null);
            run.markAutoTranslateSubmitted("100", ScheduleRunQueue.KIND_NOVEL);

            assertThat(run.snapshot())
                    .extracting(ScheduleRunQueue.Item::getKind,
                            ScheduleRunQueue.Item::getTitle,
                            ScheduleRunQueue.Item::getStatus,
                            ScheduleRunQueue.Item::isAutoTranslateSubmitted)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(
                                    ScheduleRunQueue.KIND_ILLUST, "插画标题",
                                    ScheduleRunQueue.STATUS_SKIPPED_FILTER, false),
                            org.assertj.core.groups.Tuple.tuple(
                                    ScheduleRunQueue.KIND_NOVEL, "小说标题",
                                    ScheduleRunQueue.STATUS_DOWNLOADED, true));
        }

        @Test
        @DisplayName("setMeta / mark 仅作用于已登记的作品，未登记 ID 静默忽略")
        void setMetaAndMarkOnlyAffectKnownIds() {
            ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_NOVEL);
            run.discovered("100");
            run.setMeta("100", "标题", 1, Boolean.TRUE);
            run.mark("100", ScheduleRunQueue.STATUS_DOWNLOADED, null);
            // 未登记
            run.setMeta("404", "x", 0, false);
            run.mark("404", ScheduleRunQueue.STATUS_FAILED, "boom");

            List<ScheduleRunQueue.Item> items = run.snapshot();
            assertThat(items).hasSize(1);
            ScheduleRunQueue.Item it = items.get(0);
            assertThat(it.getTitle()).isEqualTo("标题");
            assertThat(it.getXRestrict()).isEqualTo(1);
            assertThat(it.getAi()).isTrue();
            assertThat(it.getStatus()).isEqualTo(ScheduleRunQueue.STATUS_DOWNLOADED);
        }

        @Test
        @DisplayName("snapshot 返回拷贝：后续写入不影响已取出的快照")
        void snapshotIsIndependentCopy() {
            ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST);
            run.discovered("100");
            List<ScheduleRunQueue.Item> before = run.snapshot();

            run.mark("100", ScheduleRunQueue.STATUS_FAILED, "later");
            run.discovered("99");

            assertThat(before).hasSize(1);
            assertThat(before.get(0).getStatus()).isEqualTo(ScheduleRunQueue.STATUS_PENDING);
        }
    }

    @Nested
    @DisplayName("上限保护")
    class Truncation {

        @Test
        @DisplayName("超过 MAX_ITEMS 后不再记录条目并置 truncated")
        void marksTruncatedBeyondCap() {
            ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST);
            for (int i = 0; i < ScheduleRunQueue.MAX_ITEMS + 5; i++) {
                run.discovered(String.valueOf(i));
            }
            assertThat(run.truncated()).isTrue();
            assertThat(run.snapshot()).hasSize(ScheduleRunQueue.MAX_ITEMS);
        }

        @Test
        @DisplayName("未超上限时 truncated 为 false")
        void notTruncatedWithinCap() {
            ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST);
            run.discovered("1");
            run.discovered("2");
            assertThat(run.truncated()).isFalse();
        }
    }
}
