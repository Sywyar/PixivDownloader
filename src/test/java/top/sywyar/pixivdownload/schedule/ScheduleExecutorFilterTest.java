package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.download.db.TagDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ScheduleExecutor} 的服务端逐作品筛选与 params 解析（纯函数，无需 Spring 上下文）。
 */
@DisplayName("ScheduleExecutor 服务端筛选与 params 解析")
class ScheduleExecutorFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 构造辅助 ─────────────────────────────────────────────────────────────────

    private static PixivFetchService.ArtworkMeta artwork(int illustType, int xRestrict, boolean ai,
                                                         int bookmarkCount, int pageCount, List<String> tags) {
        return new PixivFetchService.ArtworkMeta(illustType, "t", xRestrict, ai, 1L, "a", null, null,
                bookmarkCount, pageCount, tags);
    }

    private static PixivFetchService.NovelDetail novel(int xRestrict, boolean ai, int bookmarkCount,
                                                       Integer wordCount, List<TagDto> tags) {
        return new PixivFetchService.NovelDetail(1L, "t", xRestrict, ai, bookmarkCount, 1L, "a", "",
                tags, null, null, null, "content", wordCount, null, null, 1, false, "", "", null, Map.of());
    }

    private static ScheduleExecutor.Filters f(String content, String ai, List<String> exact, List<String> fuzzy,
                                              String type, Integer pMin, Integer pMax,
                                              Integer wMin, Integer wMax, Integer bMin, Integer bMax) {
        return new ScheduleExecutor.Filters(content, ai, exact, fuzzy, type, pMin, pMax, wMin, wMax, bMin, bMax);
    }

    private static ScheduleExecutor.Filters passAll() {
        return f("all", "all", List.of(), List.of(), "all", null, null, null, null, null, null);
    }

    @Nested
    @DisplayName("插画筛选 artworkMatches")
    class ArtworkMatches {

        @Test
        @DisplayName("空筛选（全 all / 无范围）恒通过")
        void passAllAlwaysMatches() {
            assertThat(ScheduleExecutor.artworkMatches(
                    artwork(0, 0, false, -1, 0, List.of()), passAll())).isTrue();
        }

        @Test
        @DisplayName("内容分级 R-18+：xRestrict<1 被排除，>=1 通过")
        void contentR18Plus() {
            ScheduleExecutor.Filters f = f("r18plus", "all", List.of(), List.of(), "all",
                    null, null, null, null, null, null);
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, false, -1, 0, List.of()), f)).isFalse();
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 1, false, -1, 0, List.of()), f)).isTrue();
        }

        @Test
        @DisplayName("内容分级 全年龄/仅R-18/仅R-18G：按 xRestrict 精确分档")
        void contentRatingBuckets() {
            ScheduleExecutor.Filters safe = f("safe", "all", List.of(), List.of(), "all",
                    null, null, null, null, null, null);
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, false, -1, 0, List.of()), safe)).isTrue();
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 1, false, -1, 0, List.of()), safe)).isFalse();
            ScheduleExecutor.Filters r18 = f("r18", "all", List.of(), List.of(), "all",
                    null, null, null, null, null, null);
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 1, false, -1, 0, List.of()), r18)).isTrue();
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 2, false, -1, 0, List.of()), r18)).isFalse();
            ScheduleExecutor.Filters r18g = f("r18g", "all", List.of(), List.of(), "all",
                    null, null, null, null, null, null);
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 2, false, -1, 0, List.of()), r18g)).isTrue();
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 1, false, -1, 0, List.of()), r18g)).isFalse();
        }

        @Test
        @DisplayName("AI 筛选：exclude 排除 AI 作品，only 仅保留 AI 作品")
        void aiFilter() {
            ScheduleExecutor.Filters exclude = f("all","exclude", List.of(), List.of(), "all",
                    null, null, null, null, null, null);
            ScheduleExecutor.Filters only = f("all","only", List.of(), List.of(), "all",
                    null, null, null, null, null, null);
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, true, -1, 0, List.of()), exclude)).isFalse();
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, false, -1, 0, List.of()), exclude)).isTrue();
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, true, -1, 0, List.of()), only)).isTrue();
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, false, -1, 0, List.of()), only)).isFalse();
        }

        @Test
        @DisplayName("作品类型：illust=0 / manga=1 / ugoira=2 精确匹配 illustType")
        void typeFilter() {
            ScheduleExecutor.Filters manga = f("all","all", List.of(), List.of(), "manga",
                    null, null, null, null, null, null);
            assertThat(ScheduleExecutor.artworkMatches(artwork(1, 0, false, -1, 0, List.of()), manga)).isTrue();
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, false, -1, 0, List.of()), manga)).isFalse();
            assertThat(ScheduleExecutor.artworkMatches(artwork(2, 0, false, -1, 0, List.of()), manga)).isFalse();
        }

        @Test
        @DisplayName("页数范围：pageCount 在 [min,max] 内通过；pageCount=0（未知）跳过该判定")
        void pageRange() {
            ScheduleExecutor.Filters f = f("all","all", List.of(), List.of(), "all",
                    2, 5, null, null, null, null);
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, false, -1, 1, List.of()), f)).isFalse();
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, false, -1, 3, List.of()), f)).isTrue();
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, false, -1, 6, List.of()), f)).isFalse();
            // pageCount=0 视为未知，范围判定跳过 → 仍通过
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, false, -1, 0, List.of()), f)).isTrue();
        }

        @Test
        @DisplayName("收藏数范围：bookmarkCount>=0 时按 [min,max] 判定；-1（未返回）跳过该判定")
        void bookmarkRange() {
            ScheduleExecutor.Filters f = f("all","all", List.of(), List.of(), "all",
                    null, null, null, null, 100, null);
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, false, 50, 0, List.of()), f)).isFalse();
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, false, 150, 0, List.of()), f)).isTrue();
            // -1 表示 Pixiv 未返回收藏数，跳过判定 → 通过
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, false, -1, 0, List.of()), f)).isTrue();
        }

        @Test
        @DisplayName("标签精确匹配：相等命中，且多标签需全部命中（AND）")
        void exactTags() {
            ScheduleExecutor.Filters one = f("all","all", List.of("原神"), List.of(), "all",
                    null, null, null, null, null, null);
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, false, -1, 0, List.of("原神")), one)).isTrue();
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, false, -1, 0, List.of("genshin")), one)).isFalse();

            ScheduleExecutor.Filters both = f("all","all", List.of("a", "b"), List.of(), "all",
                    null, null, null, null, null, null);
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, false, -1, 0, List.of("a")), both)).isFalse();
            assertThat(ScheduleExecutor.artworkMatches(artwork(0, 0, false, -1, 0, List.of("a", "b")), both)).isTrue();
        }

        @Test
        @DisplayName("标签模糊匹配：子串命中（标签词元已小写）")
        void fuzzyTags() {
            ScheduleExecutor.Filters f = f("all","all", List.of(), List.of("genshin"), "all",
                    null, null, null, null, null, null);
            assertThat(ScheduleExecutor.artworkMatches(
                    artwork(0, 0, false, -1, 0, List.of("genshin impact")), f)).isTrue();
            assertThat(ScheduleExecutor.artworkMatches(
                    artwork(0, 0, false, -1, 0, List.of("原神")), f)).isFalse();
        }
    }

    @Nested
    @DisplayName("小说筛选 novelMatches")
    class NovelMatches {

        @Test
        @DisplayName("空筛选恒通过；类型/页数等插画专属项对小说无影响")
        void passAllAlwaysMatches() {
            assertThat(ScheduleExecutor.novelMatches(
                    novel(0, false, -1, null, List.of()), passAll())).isTrue();
        }

        @Test
        @DisplayName("字数范围：wordCount 在 [min,max] 内通过；null/0（未知）跳过该判定")
        void wordRange() {
            ScheduleExecutor.Filters f = f("all","all", List.of(), List.of(), "all",
                    null, null, 1000, 5000, null, null);
            assertThat(ScheduleExecutor.novelMatches(novel(0, false, -1, 500, List.of()), f)).isFalse();
            assertThat(ScheduleExecutor.novelMatches(novel(0, false, -1, 3000, List.of()), f)).isTrue();
            assertThat(ScheduleExecutor.novelMatches(novel(0, false, -1, 6000, List.of()), f)).isFalse();
            assertThat(ScheduleExecutor.novelMatches(novel(0, false, -1, null, List.of()), f)).isTrue();
            assertThat(ScheduleExecutor.novelMatches(novel(0, false, -1, 0, List.of()), f)).isTrue();
        }

        @Test
        @DisplayName("标签匹配：原名与英文翻译都纳入词元，不区分大小写")
        void tagTokensIncludeTranslation() {
            ScheduleExecutor.Filters f = f("all","all", List.of("r18"), List.of(), "all",
                    null, null, null, null, null, null);
            // 标签原名 "R-18"、翻译 "r18"：精确匹配 needle "r18" 命中翻译词元
            assertThat(ScheduleExecutor.novelMatches(
                    novel(1, false, -1, null, List.of(new TagDto("R-18", "r18"))), f)).isTrue();
        }
    }

    @Nested
    @DisplayName("params 解析")
    class ParseParams {

        @Test
        @DisplayName("parseFilters：字符串数字可解析、标签转小写、缺省项取默认")
        void parseFilters() throws Exception {
            JsonNode node = MAPPER.readTree("""
                    {"content":"r18plus","aiFilter":"exclude","tagsExact":["A","B"],"tagsFuzzy":[],
                     "typeFilter":"manga","pagesMin":"2","pagesMax":5,"bookmarksMin":100}
                    """);
            ScheduleExecutor.Filters f = ScheduleExecutor.parseFilters(node);
            assertThat(f.content()).isEqualTo("r18plus");
            assertThat(f.aiFilter()).isEqualTo("exclude");
            assertThat(f.tagsExact()).containsExactly("a", "b");
            assertThat(f.tagsFuzzy()).isEmpty();
            assertThat(f.typeFilter()).isEqualTo("manga");
            assertThat(f.pagesMin()).isEqualTo(2);
            assertThat(f.pagesMax()).isEqualTo(5);
            assertThat(f.bookmarksMin()).isEqualTo(100);
            assertThat(f.bookmarksMax()).isNull();
            assertThat(f.wordsMin()).isNull();
        }

        @Test
        @DisplayName("parseFilters：空对象取全默认（all / 空列表 / null）")
        void parseEmptyFilters() throws Exception {
            ScheduleExecutor.Filters f = ScheduleExecutor.parseFilters(MAPPER.readTree("{}"));
            assertThat(f.content()).isEqualTo("all");
            assertThat(f.aiFilter()).isEqualTo("all");
            assertThat(f.typeFilter()).isEqualTo("all");
            assertThat(f.tagsExact()).isEmpty();
            assertThat(f.pagesMin()).isNull();
        }

        @Test
        @DisplayName("parseDownload：默认小说格式 txt、合订格式 epub；空模板归一为 null；collectionId 字符串可解析")
        void parseDownload() throws Exception {
            ScheduleExecutor.Download d0 = ScheduleExecutor.parseDownload(MAPPER.readTree("{}"));
            assertThat(d0.fileNameTemplate()).isNull();
            assertThat(d0.bookmark()).isFalse();
            assertThat(d0.collectionId()).isNull();
            assertThat(d0.novelFormat()).isEqualTo("txt");
            assertThat(d0.novelMerge()).isFalse();
            assertThat(d0.novelMergeFormat()).isEqualTo("epub");

            ScheduleExecutor.Download d1 = ScheduleExecutor.parseDownload(MAPPER.readTree("""
                    {"fileNameTemplate":"   ","bookmark":true,"collectionId":"42",
                     "novelFormat":"epub","novelMerge":true,"novelMergeFormat":"txt"}
                    """));
            assertThat(d1.fileNameTemplate()).isNull(); // 纯空白归一为 null
            assertThat(d1.bookmark()).isTrue();
            assertThat(d1.collectionId()).isEqualTo(42L);
            assertThat(d1.novelFormat()).isEqualTo("epub");
            assertThat(d1.novelMerge()).isTrue();
            assertThat(d1.novelMergeFormat()).isEqualTo("txt");
        }
    }

    @Nested
    @DisplayName("水位线增量扫描 runWatermarkScan")
    class WatermarkScan {

        /** 把分页结果拼成 PageSupplier：第 N 页取 pages[N-1]，越界返回空列表。 */
        private ScheduleExecutor.PageSupplier supplier(List<List<String>> pages, AtomicInteger calls) {
            return page -> {
                calls.incrementAndGet();
                return page >= 1 && page <= pages.size() ? pages.get(page - 1) : List.of();
            };
        }

        @Test
        @DisplayName("命中水位线（id<=watermark）即停整轮：其上的新作派发、命中及其后不再处理")
        void stopsWhenReachingWatermark() throws Exception {
            List<String> dispatched = new ArrayList<>();
            // 最新在前：100 99 98 97；水位线 98 → 100/99 派发，到 98 即停（97 不处理）
            ScheduleExecutor.PageSupplier pages = supplier(
                    List.of(List.of("100", "99", "98", "97")), new AtomicInteger());
            ScheduleExecutor.WatermarkScanResult r = ScheduleExecutor.runWatermarkScan(
                    1L, pages, 98L, id -> false,
                    (id, workId) -> { dispatched.add(id); return true; }, () -> {}, () -> {},
                    ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST));
            assertThat(r.dispatched()).isEqualTo(2);
            assertThat(dispatched).containsExactly("100", "99");
            assertThat(r.newestSeen()).isEqualTo(100L);
            assertThat(r.complete()).isTrue();
        }

        @Test
        @DisplayName("兜底：连续一整页全部已下载即停（应对水位线作品被删/404 命中不到）")
        void stopsWhenWholePageAlreadyDownloaded() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            List<String> dispatched = new ArrayList<>();
            // watermark=0（首轮，无锚点）；第 1 页全部已下载 → 兜底停，第 2 页不再请求
            ScheduleExecutor.PageSupplier pages = supplier(
                    List.of(List.of("100", "99"), List.of("98", "97")), calls);
            ScheduleExecutor.WatermarkScanResult r = ScheduleExecutor.runWatermarkScan(
                    1L, pages, 0L, id -> true,
                    (id, workId) -> { dispatched.add(id); return true; }, () -> {}, () -> {},
                    ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST));
            assertThat(r.dispatched()).isZero();
            assertThat(dispatched).isEmpty();
            assertThat(calls.get()).isEqualTo(1); // 第 2 页未请求
            assertThat(r.newestSeen()).isEqualTo(100L);
            assertThat(r.complete()).isTrue();
        }

        @Test
        @DisplayName("累积 newestSeen = 所有发现到的 ID 的最大值（跨页）")
        void accumulatesNewestSeenAsMax() throws Exception {
            ScheduleExecutor.PageSupplier pages = supplier(
                    List.of(List.of("30", "20"), List.of("50", "10"), List.of()), new AtomicInteger());
            ScheduleExecutor.WatermarkScanResult r = ScheduleExecutor.runWatermarkScan(
                    1L, pages, 0L, id -> false,
                    (id, workId) -> true, () -> {}, () -> {},
                    ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST));
            assertThat(r.dispatched()).isEqualTo(4);
            assertThat(r.newestSeen()).isEqualTo(50L);
            assertThat(r.complete()).isTrue();
        }

        @Test
        @DisplayName("单作品失败隔离：dispatcher 抛普通异常仅跳过该作品，继续后续")
        void singleWorkFailureIsolated() throws Exception {
            List<String> seen = new ArrayList<>();
            ScheduleExecutor.PageSupplier pages = supplier(
                    List.of(List.of("3", "2", "1"), List.of()), new AtomicInteger());
            ScheduleExecutor.WatermarkScanResult r = ScheduleExecutor.runWatermarkScan(
                    1L, pages, 0L, id -> false,
                    (id, workId) -> {
                        seen.add(id);
                        if (workId == 2L) throw new IllegalStateException("boom");
                        return true;
                    }, () -> {}, () -> {},
                    ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST));
            assertThat(seen).containsExactly("3", "2", "1");
            assertThat(r.dispatched()).isEqualTo(2);
            assertThat(r.complete()).isFalse();
        }

        @Test
        @DisplayName("鉴权失效（PixivFetchException）上抛、停止整轮")
        void authExpiredPropagates() {
            ScheduleExecutor.PageSupplier pages = supplier(
                    List.of(List.of("2", "1")), new AtomicInteger());
            assertThatThrownBy(() -> ScheduleExecutor.runWatermarkScan(1L, pages, 0L, id -> false,
                    (id, workId) -> { throw new PixivFetchService.PixivFetchException("auth"); }, () -> {}, () -> {},
                    ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST)))
                    .isInstanceOf(PixivFetchService.PixivFetchException.class);
        }

        @Test
        @DisplayName("不设页数上限：一直翻到空页，非终止页之间执行页间延迟")
        void scansUntilEmptyPageAndDelaysBetweenPages() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger pageDelays = new AtomicInteger();
            Set<Long> none = Set.of();
            ScheduleExecutor.PageSupplier pages = page -> {
                calls.incrementAndGet();
                return page <= 3 ? List.of(String.valueOf(100000 - page)) : List.of();
            };
            ScheduleExecutor.WatermarkScanResult r = ScheduleExecutor.runWatermarkScan(
                    1L, pages, 0L, none::contains,
                    (id, workId) -> true, () -> {}, pageDelays::incrementAndGet,
                    ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST));
            assertThat(calls.get()).isEqualTo(4); // 第 4 页为空触发停止
            assertThat(pageDelays.get()).isEqualTo(3); // 只在继续翻下一页前延迟
            assertThat(r.dispatched()).isEqualTo(3);
            assertThat(r.complete()).isTrue();
        }
    }

    @Nested
    @DisplayName("已下载边界扫描 runDownloadedBoundaryScan")
    class DownloadedBoundaryScan {

        private ScheduleExecutor.PageSupplier supplier(List<List<String>> pages, AtomicInteger calls) {
            return page -> {
                calls.incrementAndGet();
                return page >= 1 && page <= pages.size() ? pages.get(page - 1) : List.of();
            };
        }

        @Test
        @DisplayName("命中第一个已下载作品即停：边界前新作派发，边界及后续页不处理")
        void stopsOnFirstDownloadedWork() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            List<String> dispatched = new ArrayList<>();
            ScheduleExecutor.PageSupplier pages = supplier(
                    List.of(List.of("100", "99", "98"), List.of("97")), calls);

            int count = ScheduleExecutor.runDownloadedBoundaryScan(
                    1L, pages, id -> id == 99L,
                    (id, workId) -> { dispatched.add(id); return true; },
                    () -> {}, () -> {}, ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST));

            assertThat(count).isEqualTo(1);
            assertThat(dispatched).containsExactly("100");
            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("未命中已下载时一直翻到空页，并在继续翻下一页前执行页间延迟")
        void scansUntilEmptyAndDelaysBetweenPages() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger pageDelays = new AtomicInteger();
            ScheduleExecutor.PageSupplier pages = supplier(
                    List.of(List.of("100"), List.of("99"), List.of()), calls);

            int count = ScheduleExecutor.runDownloadedBoundaryScan(
                    1L, pages, id -> false,
                    (id, workId) -> true,
                    () -> {}, pageDelays::incrementAndGet,
                    ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST));

            assertThat(count).isEqualTo(2);
            assertThat(calls.get()).isEqualTo(3);
            assertThat(pageDelays.get()).isEqualTo(2);
        }
    }
}
