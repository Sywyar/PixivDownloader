package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.core.db.TagDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ScheduleExecutor} 的服务端逐作品筛选、params 解析、cookie 依赖判定与增量扫描（纯函数，无需 Spring 上下文）。
 */
@DisplayName("ScheduleExecutor 服务端筛选与 params 解析")
class ScheduleExecutorFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 构造辅助 ─────────────────────────────────────────────────────────────────

    private static PixivFetchService.ArtworkMeta artwork(int illustType, int xRestrict, boolean ai,
                                                         int bookmarkCount, int pageCount, List<String> tags) {
        List<TagDto> tagDtos = tags.stream().map(t -> new TagDto(t, null)).toList();
        return new PixivFetchService.ArtworkMeta(illustType, "t", xRestrict, ai, 1L, "a", null, null,
                bookmarkCount, pageCount, tagDtos, "", null);
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

        @Test
        @DisplayName("parseDownload：队列调度项默认（并发 1 / 间隔 null / 图片间隔 null / 不校验目录），有值时按毫秒整数解析")
        void parseDownloadQueueSettings() throws Exception {
            ScheduleExecutor.Download d0 = ScheduleExecutor.parseDownload(MAPPER.readTree("{}"));
            assertThat(d0.concurrent()).isEqualTo(1);
            assertThat(d0.intervalMs()).isNull();
            assertThat(d0.imageDelayMs()).isNull();
            assertThat(d0.verifyFiles()).isFalse();

            ScheduleExecutor.Download d1 = ScheduleExecutor.parseDownload(MAPPER.readTree("""
                    {"concurrent":4,"intervalMs":2000,"imageDelayMs":"250","verifyFiles":true}
                    """));
            assertThat(d1.concurrent()).isEqualTo(4);
            assertThat(d1.intervalMs()).isEqualTo(2000L);
            assertThat(d1.imageDelayMs()).isEqualTo(250);
            assertThat(d1.verifyFiles()).isTrue();

            // 并发数下限为 1（0 / 负值归一为 1）
            assertThat(ScheduleExecutor.parseDownload(MAPPER.readTree("{\"concurrent\":0}")).concurrent())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("parseDownload：redownloadDeleted 缺省为 false（旧任务快照按不允许重下已删除作品处理），显式 true 可解析")
        void parseDownloadRedownloadDeleted() throws Exception {
            assertThat(ScheduleExecutor.parseDownload(MAPPER.readTree("{}")).redownloadDeleted()).isFalse();
            assertThat(ScheduleExecutor.parseDownload(MAPPER.readTree("{\"redownloadDeleted\":true}"))
                    .redownloadDeleted()).isTrue();
        }
    }

    @Nested
    @DisplayName("cookie 依赖型判定 isCookieDependent")
    class CookieDependent {

        private boolean dep(String json) throws Exception {
            return ScheduleExecutor.isCookieDependent(MAPPER.readTree(json));
        }

        @Test
        @DisplayName("filters.content != safe 即依赖（含 all）")
        void contentNonSafeIsDependent() throws Exception {
            assertThat(dep("{\"filters\":{\"content\":\"all\"}}")).isTrue();
            assertThat(dep("{\"filters\":{\"content\":\"r18\"}}")).isTrue();
            assertThat(dep("{\"filters\":{\"content\":\"r18plus\"}}")).isTrue();
            assertThat(dep("{\"filters\":{\"content\":\"safe\"}}")).isFalse();
        }

        @Test
        @DisplayName("source.mode == r18 即依赖")
        void sourceModeR18IsDependent() throws Exception {
            assertThat(dep("{\"filters\":{\"content\":\"safe\"},\"source\":{\"mode\":\"r18\"}}")).isTrue();
            assertThat(dep("{\"filters\":{\"content\":\"safe\"},\"source\":{\"mode\":\"all\"}}")).isFalse();
        }

        @Test
        @DisplayName("download.bookmark == true 即依赖")
        void bookmarkIsDependent() throws Exception {
            assertThat(dep("{\"filters\":{\"content\":\"safe\"},\"download\":{\"bookmark\":true}}")).isTrue();
            assertThat(dep("{\"filters\":{\"content\":\"safe\"},\"download\":{\"bookmark\":false}}")).isFalse();
        }

        @Test
        @DisplayName("纯全年龄 + 非 r18 来源 + 不收藏 → 非依赖")
        void plainSafeIsNotDependent() throws Exception {
            assertThat(dep("{\"filters\":{\"content\":\"safe\"},\"source\":{\"mode\":\"safe\"},\"download\":{\"bookmark\":false}}"))
                    .isFalse();
        }
    }

    // 账号私有来源类型（isAccountScopedType）与水位线模式判定（isWatermarkMode）已随发现 / 派发逻辑迁入
    // 各 ScheduledSource（schedule.source），其对应单测移至 ScheduledSourceTest。

    @Nested
    @DisplayName("水位线增量扫描 runWatermarkScan")
    class WatermarkScan {

        /** 把分页结果拼成 PageSupplier：第 N 页取 pages[N-1]，越界返回空列表。 */
        private PageSupplier supplier(List<List<String>> pages, AtomicInteger calls) {
            return page -> {
                calls.incrementAndGet();
                return page >= 1 && page <= pages.size() ? pages.get(page - 1) : List.of();
            };
        }

        @Test
        @DisplayName("命中水位线（id<=watermark）即停整轮：其上的新作派发、命中及其后不再处理")
        void stopsWhenReachingWatermark() throws Exception {
            List<String> dispatched = new ArrayList<>();
            PageSupplier pages = supplier(
                    List.of(List.of("100", "99", "98", "97")), new AtomicInteger());
            ScheduleExecutor.WatermarkScanResult r = ScheduleExecutor.runWatermarkScan(
                    pages, 98L, id -> false,
                    (id, workId) -> { dispatched.add(id); return true; }, () -> {}, () -> {},
                    ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST), 0);
            assertThat(r.queued()).isEqualTo(2);
            assertThat(dispatched).containsExactly("100", "99");
            assertThat(r.newestSeen()).isEqualTo(100L);
        }

        @Test
        @DisplayName("兜底：连续一整页全部已下载即停（应对水位线作品被删/404 命中不到）")
        void stopsWhenWholePageAlreadyDownloaded() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            List<String> dispatched = new ArrayList<>();
            PageSupplier pages = supplier(
                    List.of(List.of("100", "99"), List.of("98", "97")), calls);
            ScheduleExecutor.WatermarkScanResult r = ScheduleExecutor.runWatermarkScan(
                    pages, 0L, id -> true,
                    (id, workId) -> { dispatched.add(id); return true; }, () -> {}, () -> {},
                    ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST), 0);
            assertThat(r.queued()).isEqualTo(2);
            assertThat(dispatched).isEmpty();
            assertThat(calls.get()).isEqualTo(1); // 第 2 页未请求
            assertThat(r.newestSeen()).isEqualTo(100L);
        }

        @Test
        @DisplayName("累积 newestSeen = 所有发现到的 ID 的最大值（跨页）")
        void accumulatesNewestSeenAsMax() throws Exception {
            PageSupplier pages = supplier(
                    List.of(List.of("30", "20"), List.of("50", "10"), List.of()), new AtomicInteger());
            ScheduleExecutor.WatermarkScanResult r = ScheduleExecutor.runWatermarkScan(
                    pages, 0L, id -> false,
                    (id, workId) -> true, () -> {}, () -> {},
                    ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST), 0);
            assertThat(r.queued()).isEqualTo(4);
            assertThat(r.newestSeen()).isEqualTo(50L);
        }

        @Test
        @DisplayName("单作品被隔离（dispatcher 返 false）仍计入队列数，且 watermark 仍可推进")
        void isolatedWorkStillAdvancesWatermark() throws Exception {
            List<String> seen = new ArrayList<>();
            PageSupplier pages = supplier(
                    List.of(List.of("3", "2", "1"), List.of()), new AtomicInteger());
            ScheduleExecutor.WatermarkScanResult r = ScheduleExecutor.runWatermarkScan(
                    pages, 0L, id -> false,
                    (id, workId) -> {
                        seen.add(id);
                        return workId != 2L; // workId=2 被 WorkRunner 隔离后返 false
                    }, () -> {}, () -> {},
                    ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST), 0);
            assertThat(seen).containsExactly("3", "2", "1");
            assertThat(r.queued()).isEqualTo(3);
            assertThat(r.newestSeen()).isEqualTo(3L);
        }

        @Test
        @DisplayName("挂起信号（过度访问）上抛、停止整轮且不返回（不推进 watermark）")
        void overuseWarningPropagates() {
            PageSupplier pages = supplier(
                    List.of(List.of("2", "1")), new AtomicInteger());
            assertThatThrownBy(() -> ScheduleExecutor.runWatermarkScan(pages, 0L, id -> false,
                    (id, workId) -> { throw new OveruseWarningException(123L, ""); }, () -> {}, () -> {},
                    ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST), 0))
                    .isInstanceOf(OveruseWarningException.class);
        }

        @Test
        @DisplayName("不设页数上限：一直翻到空页，非终止页之间执行页间延迟")
        void scansUntilEmptyPageAndDelaysBetweenPages() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger pageDelays = new AtomicInteger();
            Set<Long> none = Set.of();
            PageSupplier pages = page -> {
                calls.incrementAndGet();
                return page <= 3 ? List.of(String.valueOf(100000 - page)) : List.of();
            };
            ScheduleExecutor.WatermarkScanResult r = ScheduleExecutor.runWatermarkScan(
                    pages, 0L, none::contains,
                    (id, workId) -> true, () -> {}, pageDelays::incrementAndGet,
                    ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST), 0);
            assertThat(calls.get()).isEqualTo(4); // 第 4 页为空触发停止
            assertThat(pageDelays.get()).isEqualTo(3); // 只在继续翻下一页前延迟
            assertThat(r.queued()).isEqualTo(3);
        }

        @Test
        @DisplayName("首轮封顶（queueLimit>0）：入队数达上限即停，newestSeen 仍为最新 ID（水位线推进到最新）")
        void firstRunLimitStopsAtCapButKeepsNewestSeen() throws Exception {
            List<String> dispatched = new ArrayList<>();
            PageSupplier pages = supplier(
                    List.of(List.of("100", "99", "98", "97", "96")), new AtomicInteger());
            ScheduleExecutor.WatermarkScanResult r = ScheduleExecutor.runWatermarkScan(
                    pages, 0L, id -> false,
                    (id, workId) -> { dispatched.add(id); return true; }, () -> {}, () -> {},
                    ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST), 2);
            assertThat(r.queued()).isEqualTo(2);
            assertThat(dispatched).containsExactly("100", "99"); // 只下最新 2 个
            assertThat(r.newestSeen()).isEqualTo(100L); // 水位线照常推进到最新，更老积压永久跳过
        }

        @Test
        @DisplayName("首轮封顶按入队数计算：已下载跳过也占额度")
        void firstRunLimitCountsQueuedWorks() throws Exception {
            List<String> dispatched = new ArrayList<>();
            PageSupplier pages = supplier(
                    List.of(List.of("100", "99", "98", "97", "96")), new AtomicInteger());
            ScheduleExecutor.WatermarkScanResult r = ScheduleExecutor.runWatermarkScan(
                    pages, 0L, id -> id == 100L || id == 99L, // 最新两个已下载
                    (id, workId) -> { dispatched.add(id); return true; }, () -> {}, () -> {},
                    ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST), 2);
            assertThat(r.queued()).isEqualTo(2);
            assertThat(dispatched).isEmpty(); // 已下载已入队并占满额度，不再继续凑新下载
            assertThat(r.newestSeen()).isEqualTo(100L);
        }
    }

    @Nested
    @DisplayName("已下载边界扫描 runDownloadedBoundaryScan")
    class DownloadedBoundaryScan {

        private PageSupplier supplier(List<List<String>> pages, AtomicInteger calls) {
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
            PageSupplier pages = supplier(
                    List.of(List.of("100", "99", "98"), List.of("97")), calls);

            int count = ScheduleExecutor.runDownloadedBoundaryScan(
                    pages, id -> id == 99L,
                    (id, workId) -> { dispatched.add(id); return true; },
                    () -> {}, () -> {}, ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST), 0);

            assertThat(count).isEqualTo(1);
            assertThat(dispatched).containsExactly("100");
            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("未命中已下载时一直翻到空页，并在继续翻下一页前执行页间延迟")
        void scansUntilEmptyAndDelaysBetweenPages() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger pageDelays = new AtomicInteger();
            PageSupplier pages = supplier(
                    List.of(List.of("100"), List.of("99"), List.of()), calls);

            int count = ScheduleExecutor.runDownloadedBoundaryScan(
                    pages, id -> false,
                    (id, workId) -> true,
                    () -> {}, pageDelays::incrementAndGet,
                    ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST), 0);

            assertThat(count).isEqualTo(2);
            assertThat(calls.get()).isEqualTo(3);
            assertThat(pageDelays.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("每轮上限（queueLimit>0）：入队数达上限即停本轮，剩余作品留待下一轮")
        void perRunLimitStopsAtCap() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            List<String> dispatched = new ArrayList<>();
            PageSupplier pages = supplier(
                    List.of(List.of("100", "99", "98"), List.of("97", "96")), calls);

            int count = ScheduleExecutor.runDownloadedBoundaryScan(
                    pages, id -> false,
                    (id, workId) -> { dispatched.add(id); return true; },
                    () -> {}, () -> {}, ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST), 2);

            assertThat(count).isEqualTo(2);
            assertThat(dispatched).containsExactly("100", "99");
            assertThat(calls.get()).isEqualTo(1); // 第 1 页内就凑满上限，未翻第 2 页
        }

        @Test
        @DisplayName("每轮上限按入队数计算：单作品被隔离也占额度")
        void perRunLimitCountsQueuedWorks() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            List<String> attempted = new ArrayList<>();
            PageSupplier pages = supplier(
                    List.of(List.of("100", "99", "98"), List.of("97", "96")), calls);

            int count = ScheduleExecutor.runDownloadedBoundaryScan(
                    pages, id -> false,
                    (id, workId) -> {
                        attempted.add(id);
                        return workId != 100L;
                    },
                    () -> {}, () -> {}, ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST), 2);

            assertThat(count).isEqualTo(2);
            assertThat(attempted).containsExactly("100", "99");
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("全量来源每轮上限扫描 runFullDiscoveryCapScan")
    class FullDiscoveryCapScan {

        @Test
        @DisplayName("已下载跳过不占额度：免费推进窗口，每轮下满 N 个尚未下载的新作")
        void downloadedSkipsAreFree() throws Exception {
            // 表头 2 个已下载、其后 3 个未下载；queueLimit=2 应免费跳过已下载、再下满 2 个新作（103、102）
            List<String> attempted = new ArrayList<>();
            java.util.function.LongPredicate downloaded = id -> id == 105L || id == 104L;
            int queued = ScheduleExecutor.runFullDiscoveryCapScan(
                    List.of("105", "104", "103", "102", "101"), downloaded,
                    (id, workId) -> { attempted.add(id); return true; },
                    () -> {}, ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST), 2);

            assertThat(queued).isEqualTo(2);
            assertThat(attempted).containsExactly("103", "102");
        }

        @Test
        @DisplayName("第二轮从已下载的表头免费滑过、继续抽干更早的积压（不再卡在第一批）")
        void drainsBacklogAcrossRounds() throws Exception {
            // 模拟第二轮：表头 50 个已下载（其后还有未下载积压），queueLimit=2 不应被已下载吃满而空跑
            java.util.function.LongPredicate downloaded = id -> id >= 51L; // 100..51 已下载，50..1 未下载
            List<String> ids = new ArrayList<>();
            for (long i = 100; i >= 1; i--) ids.add(String.valueOf(i));
            List<String> attempted = new ArrayList<>();
            int queued = ScheduleExecutor.runFullDiscoveryCapScan(
                    ids, downloaded,
                    (id, workId) -> { attempted.add(id); return true; },
                    () -> {}, ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST), 2);

            assertThat(queued).isEqualTo(2);
            assertThat(attempted).containsExactly("50", "49"); // 跳过表头 50 个已下载、抽到最旧积压
        }

        @Test
        @DisplayName("queueLimit<=0 不限：全部未下载作品都派发")
        void unlimitedDispatchesAll() throws Exception {
            List<String> attempted = new ArrayList<>();
            int queued = ScheduleExecutor.runFullDiscoveryCapScan(
                    List.of("105", "104", "103"), id -> false,
                    (id, workId) -> { attempted.add(id); return true; },
                    () -> {}, ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST), 0);

            assertThat(queued).isEqualTo(3);
            assertThat(attempted).containsExactly("105", "104", "103");
        }

        @Test
        @DisplayName("筛选跳过仍占额度：dispatcher 返 false（被筛选）也累计每轮额度")
        void filterSkipsConsumeBudget() throws Exception {
            List<String> attempted = new ArrayList<>();
            int queued = ScheduleExecutor.runFullDiscoveryCapScan(
                    List.of("105", "104", "103", "102"), id -> false,
                    (id, workId) -> { attempted.add(id); return false; }, // 全部被筛选跳过
                    () -> {}, ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_ILLUST), 2);

            assertThat(queued).isEqualTo(2);
            assertThat(attempted).containsExactly("105", "104");
        }
    }
}
