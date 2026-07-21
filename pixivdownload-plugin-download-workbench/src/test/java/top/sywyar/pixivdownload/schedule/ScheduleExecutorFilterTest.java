package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.schedule.snapshot.ScheduleTaskSnapshot;
import top.sywyar.pixivdownload.schedule.snapshot.ScheduleTaskSnapshot.Download;
import top.sywyar.pixivdownload.schedule.snapshot.ScheduleTaskSnapshot.Filters;
import top.sywyar.pixivdownload.schedule.snapshot.ScheduleWorkFilter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ScheduleTaskSnapshot} 与 {@link ScheduleWorkFilter} 的纯函数测试。
 */
@DisplayName("计划任务快照与插画筛选")
class ScheduleExecutorFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 构造辅助 ─────────────────────────────────────────────────────────────────

    private static PixivFetchService.ArtworkMeta artwork(int illustType, int xRestrict, boolean ai,
                                                         int bookmarkCount, int pageCount, List<String> tags) {
        List<TagDto> tagDtos = tags.stream().map(t -> new TagDto(t, null)).toList();
        return new PixivFetchService.ArtworkMeta(illustType, "t", xRestrict, ai, 1L, "a", null, null,
                bookmarkCount, pageCount, tagDtos, "", null);
    }

    private static Filters f(String content, String ai, List<String> exact, List<String> fuzzy,
                             String type, Integer pMin, Integer pMax,
                             Integer wMin, Integer wMax, Integer bMin, Integer bMax) {
        return new Filters(content, ai, exact, fuzzy, type, pMin, pMax, wMin, wMax, bMin, bMax);
    }

    private static Filters passAll() {
        return f("all", "all", List.of(), List.of(), "all", null, null, null, null, null, null);
    }

    @Nested
    @DisplayName("插画筛选 artworkMatches")
    class ArtworkMatches {

        @Test
        @DisplayName("空筛选（全 all / 无范围）恒通过")
        void passAllAlwaysMatches() {
            assertThat(ScheduleWorkFilter.artworkMatches(
                    artwork(0, 0, false, -1, 0, List.of()), passAll())).isTrue();
        }

        @Test
        @DisplayName("内容分级 R-18+：xRestrict<1 被排除，>=1 通过")
        void contentR18Plus() {
            Filters f = f("r18plus", "all", List.of(), List.of(), "all",
                    null, null, null, null, null, null);
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, false, -1, 0, List.of()), f)).isFalse();
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 1, false, -1, 0, List.of()), f)).isTrue();
        }

        @Test
        @DisplayName("内容分级 全年龄/仅R-18/仅R-18G：按 xRestrict 精确分档")
        void contentRatingBuckets() {
            Filters safe = f("safe", "all", List.of(), List.of(), "all",
                    null, null, null, null, null, null);
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, false, -1, 0, List.of()), safe)).isTrue();
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 1, false, -1, 0, List.of()), safe)).isFalse();
            Filters r18 = f("r18", "all", List.of(), List.of(), "all",
                    null, null, null, null, null, null);
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 1, false, -1, 0, List.of()), r18)).isTrue();
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 2, false, -1, 0, List.of()), r18)).isFalse();
            Filters r18g = f("r18g", "all", List.of(), List.of(), "all",
                    null, null, null, null, null, null);
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 2, false, -1, 0, List.of()), r18g)).isTrue();
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 1, false, -1, 0, List.of()), r18g)).isFalse();
        }

        @Test
        @DisplayName("AI 筛选：exclude 排除 AI 作品，only 仅保留 AI 作品")
        void aiFilter() {
            Filters exclude = f("all","exclude", List.of(), List.of(), "all",
                    null, null, null, null, null, null);
            Filters only = f("all","only", List.of(), List.of(), "all",
                    null, null, null, null, null, null);
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, true, -1, 0, List.of()), exclude)).isFalse();
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, false, -1, 0, List.of()), exclude)).isTrue();
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, true, -1, 0, List.of()), only)).isTrue();
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, false, -1, 0, List.of()), only)).isFalse();
        }

        @Test
        @DisplayName("作品类型：illust=0 / manga=1 / ugoira=2 精确匹配 illustType")
        void typeFilter() {
            Filters manga = f("all","all", List.of(), List.of(), "manga",
                    null, null, null, null, null, null);
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(1, 0, false, -1, 0, List.of()), manga)).isTrue();
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, false, -1, 0, List.of()), manga)).isFalse();
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(2, 0, false, -1, 0, List.of()), manga)).isFalse();
        }

        @Test
        @DisplayName("页数范围：pageCount 在 [min,max] 内通过；pageCount=0（未知）跳过该判定")
        void pageRange() {
            Filters f = f("all","all", List.of(), List.of(), "all",
                    2, 5, null, null, null, null);
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, false, -1, 1, List.of()), f)).isFalse();
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, false, -1, 3, List.of()), f)).isTrue();
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, false, -1, 6, List.of()), f)).isFalse();
            // pageCount=0 视为未知，范围判定跳过 → 仍通过
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, false, -1, 0, List.of()), f)).isTrue();
        }

        @Test
        @DisplayName("收藏数范围：bookmarkCount>=0 时按 [min,max] 判定；-1（未返回）跳过该判定")
        void bookmarkRange() {
            Filters f = f("all","all", List.of(), List.of(), "all",
                    null, null, null, null, 100, null);
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, false, 50, 0, List.of()), f)).isFalse();
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, false, 150, 0, List.of()), f)).isTrue();
            // -1 表示 Pixiv 未返回收藏数，跳过判定 → 通过
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, false, -1, 0, List.of()), f)).isTrue();
        }

        @Test
        @DisplayName("标签精确匹配：相等命中，且多标签需全部命中（AND）")
        void exactTags() {
            Filters one = f("all","all", List.of("原神"), List.of(), "all",
                    null, null, null, null, null, null);
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, false, -1, 0, List.of("原神")), one)).isTrue();
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, false, -1, 0, List.of("genshin")), one)).isFalse();

            Filters both = f("all","all", List.of("a", "b"), List.of(), "all",
                    null, null, null, null, null, null);
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, false, -1, 0, List.of("a")), both)).isFalse();
            assertThat(ScheduleWorkFilter.artworkMatches(artwork(0, 0, false, -1, 0, List.of("a", "b")), both)).isTrue();
        }

        @Test
        @DisplayName("标签模糊匹配：子串命中（标签词元已小写）")
        void fuzzyTags() {
            Filters f = f("all","all", List.of(), List.of("genshin"), "all",
                    null, null, null, null, null, null);
            assertThat(ScheduleWorkFilter.artworkMatches(
                    artwork(0, 0, false, -1, 0, List.of("genshin impact")), f)).isTrue();
            assertThat(ScheduleWorkFilter.artworkMatches(
                    artwork(0, 0, false, -1, 0, List.of("原神")), f)).isFalse();
        }
    }

    @Nested
    @DisplayName("params 解析")
    class ParseParams {

        @Test
        @DisplayName("parse：统一解析作品类型、来源、设置与非负抓取上限")
        void parseSnapshot() throws Exception {
            ScheduleTaskSnapshot snapshot = ScheduleTaskSnapshot.parse(MAPPER, """
                    {"kind":"NOVEL","source":{"word":"cat","mode":"r18"},
                     "filters":{"content":"safe"},"download":{"concurrent":3},"fetchLimit":-2}
                    """);

            assertThat(snapshot.novel()).isTrue();
            assertThat(snapshot.source().path("word").asText()).isEqualTo("cat");
            assertThat(snapshot.filters().content()).isEqualTo("safe");
            assertThat(snapshot.download().concurrent()).isEqualTo(3);
            assertThat(snapshot.fetchLimit()).isZero();
            assertThat(snapshot.cookieDependent()).isTrue();
        }

        @Test
        @DisplayName("parseFilters：字符串数字可解析、标签转小写、缺省项取默认")
        void parseFilters() throws Exception {
            JsonNode node = MAPPER.readTree("""
                    {"content":"r18plus","aiFilter":"exclude","tagsExact":["A","B"],"tagsFuzzy":[],
                     "typeFilter":"manga","pagesMin":"2","pagesMax":5,"bookmarksMin":100}
                    """);
            Filters f = ScheduleTaskSnapshot.parseFilters(node);
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
            Filters f = ScheduleTaskSnapshot.parseFilters(MAPPER.readTree("{}"));
            assertThat(f.content()).isEqualTo("all");
            assertThat(f.aiFilter()).isEqualTo("all");
            assertThat(f.typeFilter()).isEqualTo("all");
            assertThat(f.tagsExact()).isEmpty();
            assertThat(f.pagesMin()).isNull();
        }

        @Test
        @DisplayName("parseDownload：默认小说格式 txt、合订格式 epub；空模板归一为 null；collectionId 字符串可解析")
        void parseDownload() throws Exception {
            Download d0 = ScheduleTaskSnapshot.parseDownload(MAPPER.readTree("{}"));
            assertThat(d0.fileNameTemplate()).isNull();
            assertThat(d0.bookmark()).isFalse();
            assertThat(d0.collectionId()).isNull();
            assertThat(d0.novelFormat()).isEqualTo("txt");
            assertThat(d0.novelMerge()).isFalse();
            assertThat(d0.novelMergeFormat()).isEqualTo("epub");

            Download d1 = ScheduleTaskSnapshot.parseDownload(MAPPER.readTree("""
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
            Download d0 = ScheduleTaskSnapshot.parseDownload(MAPPER.readTree("{}"));
            assertThat(d0.concurrent()).isEqualTo(1);
            assertThat(d0.intervalMs()).isNull();
            assertThat(d0.imageDelayMs()).isNull();
            assertThat(d0.verifyFiles()).isFalse();

            Download d1 = ScheduleTaskSnapshot.parseDownload(MAPPER.readTree("""
                    {"concurrent":4,"intervalMs":2000,"imageDelayMs":"250","verifyFiles":true}
                    """));
            assertThat(d1.concurrent()).isEqualTo(4);
            assertThat(d1.intervalMs()).isEqualTo(2000L);
            assertThat(d1.imageDelayMs()).isEqualTo(250);
            assertThat(d1.verifyFiles()).isTrue();

            // 并发数下限为 1（0 / 负值归一为 1）
            assertThat(ScheduleTaskSnapshot.parseDownload(MAPPER.readTree("{\"concurrent\":0}")).concurrent())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("parseDownload：redownloadDeleted 缺省为 false（旧任务快照按不允许重下已删除作品处理），显式 true 可解析")
        void parseDownloadRedownloadDeleted() throws Exception {
            assertThat(ScheduleTaskSnapshot.parseDownload(MAPPER.readTree("{}")).redownloadDeleted()).isFalse();
            assertThat(ScheduleTaskSnapshot.parseDownload(MAPPER.readTree("{\"redownloadDeleted\":true}"))
                    .redownloadDeleted()).isTrue();
        }
    }

    @Nested
    @DisplayName("cookie 依赖型判定 isCookieDependent")
    class CookieDependent {

        private boolean dep(String json) throws Exception {
            return ScheduleTaskSnapshot.from(MAPPER.readTree(json)).cookieDependent();
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
}
