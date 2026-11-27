package top.sywyar.pixivdownload.pixivcontent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;

import static org.junit.jupiter.api.Assertions.*;

class NovelContentQueriesTest {
    private static final String META = """
            {"id":24,"title":"Series","userId":"3","userName":"Artist","caption":"About it","publishedContentCount":72}
            """;
    private static final String CHAPTER = """
            {"id":"88","title":"Chapter","userId":"3","series":{"id":24,"contentOrder":31},"characterCount":16}
            """;
    private static final String NOVEL = """
            {"id":"88","title":"Story","userId":"3","userName":"Artist","language":"ja","tags":{"tags":[{"tag":"ink"}]},
             "seriesNavData":{"seriesId":"24","title":"Series","order":31},"description":"<b>Introduction</b>",
             "createDate":"2026-01-02T12:00:00+09:00","uploadDate":"2026-01-03T12:00:00+09:00",
             "xRestrict":0,"aiType":1,"characterCount":123,"viewCount":900,
             "coverUrl":"https://i.pximg.net/cover.jpg",
             "textEmbeddedImages":{"7":{"novelImageId":"7","urls":{"original":"https://i.pximg.net/7.jpg"}}},
             "content":"[chapter:序]\\n[uploadedimage:7][pixivimage:17-3][pixivimage:99][pixivimage:17-3]"}
            """;

    @Test void searchEncodesAKeywordOnceAndRetainsPaginationAcrossMaskedItems() {
        var queries = queries((uri, cookie) -> {
            assertEquals("/ajax/search/novels/%E7%8C%AB%20%2B%2050%25", uri.getRawPath());
            assertTrue(uri.getRawQuery().contains("word=%E7%8C%AB%20%2B%2050%25"));
            assertTrue(uri.getRawQuery().contains("p=2"));
            assertEquals("selected", cookie);
            return envelope("""
                    {"novel":{"data":[{"isAdContainer":true},{"isMasked":true},
                    {"id":"88","title":"Story","userId":"3","userName":"Artist","textCount":91}],
                    "total":230,"lastPage":3}}
                    """);
        });
        var page = queries.search("猫 + 50%", "date_d", "safe", "s_tag", null, null, 2, "selected");
        assertEquals(230, page.total());
        assertTrue(page.hasNext());
        var result = page.items().getFirst();
        assertEquals(91, result.characterCount());
        assertEquals("/api/pixiv-content/novels/88", result.detailPath());
        assertTrue(page.filter(id -> false).items().isEmpty());
    }

    @Test void novelReturnsPixivMarkupUnchangedWithTagsAndSeriesMembership() {
        var queries = queries((uri, cookie) -> {
            assertEquals("selected", cookie);
            if (uri.getPath().equals("/ajax/novel/88")) return envelope(NOVEL);
            assertEquals("/ajax/novel/88/insert_illusts", uri.getPath());
            assertEquals("id[]=17-3&id[]=99", uri.getQuery());
            return envelope("""
                    {"17-3":{"visible":true,"id":"17","page":3},"99":{"visible":false}}
                    """);
        });
        var novel = queries.novel(88, "selected");
        assertEquals("[chapter:序]\n[uploadedimage:7][pixivimage:17-3][pixivimage:99][pixivimage:17-3]", novel.content());
        assertEquals("pixiv-novel-text", novel.contentFormat());
        assertEquals(List.of("ink"), novel.tags());
        assertEquals(31, novel.series().order());
        assertEquals(900, novel.viewCount());
        assertEquals(Instant.parse("2026-01-02T03:00:00Z"), novel.createdAt());
        assertEquals(2, novel.images().size());
        assertEquals("/api/pixiv-content/novels/88/images/7", novel.images().getFirst().imagePath());
        assertEquals("/api/pixiv-content/artworks/17/pages/2/image", novel.images().getLast().imagePath());
        assertEquals(List.of(novel.images().getFirst()), novel.filterIllustrations(id -> false).images());
        assertEquals(502, assertThrows(ContentFailure.class, () -> queries((uri, cookie) ->
                envelope("{\"id\":89}")).novel(88, null)).status);
        assertEquals(403, assertThrows(ContentFailure.class, () -> queries((uri, cookie) ->
                envelope("{\"id\":88,\"isMasked\":true}")).novel(88, null)).status);
    }

    @Test void coverAndUploadRequireTrustedTargetsFromTheRequestedNovel() {
        var query = queries((uri, cookie) -> envelope(NOVEL));
        assertEquals("https://i.pximg.net/cover.jpg", query.cover(88, null).toString());
        assertEquals("https://i.pximg.net/7.jpg", query.uploadedImage(88, 7, null).toString());
        assertEquals(404, assertThrows(ContentFailure.class, () -> query.uploadedImage(88, 8, null)).status);
        var removed = queries((uri, cookie) -> envelope(NOVEL.replace("[uploadedimage:7]", "[uploadedimage:70]")));
        assertEquals(404, assertThrows(ContentFailure.class, () -> removed.uploadedImage(88, 7, null)).status);
        var foreign = queries((uri, cookie) -> envelope(NOVEL.replace("i.pximg.net", "example.org")));
        assertEquals(502, assertThrows(ContentFailure.class, () -> foreign.cover(88, null)).status);
        assertEquals(502, assertThrows(ContentFailure.class, () -> foreign.uploadedImage(88, 7, null)).status);
        var mismatch = queries((uri, cookie) -> envelope(NOVEL.replace("\"novelImageId\":\"7\"", "\"novelImageId\":\"8\"")));
        assertEquals(502, assertThrows(ContentFailure.class, () -> mismatch.uploadedImage(88, 7, null)).status);
    }

    @Test void artworkReferencesAreCheckedInBoundedBatchesAndNeverRetargeted() {
        var raw = new StringBuilder();
        for (int i = 1; i <= 31; i++) raw.append("[pixivimage:").append(i).append(']');
        var batches = new java.util.ArrayList<Integer>();
        var context = new ContentQueries((uri, cookie) -> {
            batches.add(uri.getQuery().split("&").length);
            return envelope("{}");
        }, null, new JsonMapper());
        var empty = new JsonMapper().createObjectNode();
        assertTrue(NovelImages.resolve(context, 88, raw.toString(), empty, null).isEmpty());
        assertEquals(List.of(30, 1), batches);
        var mismatch = new ContentQueries((uri, cookie) ->
                envelope("{\"17-3\":{\"visible\":true,\"id\":17,\"page\":1}}"), null, new JsonMapper());
        assertEquals(502, assertThrows(ContentFailure.class, () ->
                NovelImages.resolve(mismatch, 88, "[pixivimage:17-3]", empty, null)).status);
        for (int i = 32; i <= 331; i++) raw.append("[pixivimage:").append(i).append(']');
        assertEquals("PIXIV_RESPONSE_TOO_LARGE", assertThrows(ContentFailure.class, () ->
                NovelImages.resolve(context, 88, raw.toString(), empty, null)).code);
        assertEquals(List.of(30, 1), batches);
    }

    @Test void authorNovelsReadOneSelectedPageNewestFirstAndRejectForeignAuthors() {
        var index = new JsonMapper().createObjectNode();
        var ids = index.putObject("novels");
        for (int i = 1; i <= 26; i++) ids.putNull(Integer.toString(i));
        var queries = queries((uri, cookie) -> {
            assertEquals("selected", cookie);
            if (uri.getPath().equals("/ajax/user/3/profile/all")) return envelope(index.toString());
            assertEquals("/ajax/user/3/novels", uri.getPath());
            assertEquals("ids[]=2&ids[]=1", uri.getQuery());
            return envelope("""
                    {"1":{"isMasked":true},"2":{"id":"2","title":"N","userName":"A","userId":"3",
                     "url":"https://i.pximg.net/c.jpg","tags":["ink"],"bookmarkCount":18,"aiType":2}}
                    """);
        });
        var page = queries.userNovels(3, 2, "selected");
        assertEquals(26, page.total());
        assertFalse(page.hasNext());
        var novel = page.items().getFirst();
        assertEquals("/api/pixiv-content/novels/2/cover", novel.coverPath());
        assertEquals(18, novel.bookmarkCount());
        assertEquals(List.of("ink"), novel.tags());
        assertTrue(queries.userNovels(3, Integer.MAX_VALUE, "selected").items().isEmpty());
        var foreign = queries((uri, cookie) -> envelope(uri.getPath().endsWith("/all")
                ? "{\"novels\":{\"2\":null}}" : "{\"2\":{\"id\":\"2\",\"title\":\"N\",\"userId\":\"84\",\"userName\":\"A\"}}"));
        assertEquals(502, assertThrows(ContentFailure.class, () -> foreign.userNovels(3, 1, null)).status);
    }

    @Test void datedWeeklyRankingsPreserveOriginalRankAndResolveLastPage() {
        String response = """
                {"start":"11/20/2026","end":"11/26/2026","date":null,
                 "display_a":{"mode":"weekly","page":2,"next":null,
                 "rank_a":[{"rank":55,"id":"88","title":"Story","user_id":"3","user_name":"Author",
                   "character_count":"320","x_restrict":"0","ai_type":"1","tag_a":["ink"]}]}}
                """;
        var query = queries((uri, cookie) -> {
            assertEquals("/ajax/ranking/novel", uri.getPath());
            assertEquals("mode=weekly&p=2&lang=en&date=20261126", uri.getQuery());
            return envelope(response);
        });
        var date = LocalDate.of(2026, 11, 26);
        var result = query.rankings("weekly", date, 2, null);
        assertEquals(date, result.rankingDate());
        assertEquals(date.minusDays(6), result.periodStart());
        assertFalse(result.hasNext());
        assertEquals(55, result.items().getFirst().rank());
        assertEquals(320, result.items().getFirst().novel().characterCount());
        assertTrue(result.filter(id -> false).items().isEmpty());
        assertEquals(502, assertThrows(ContentFailure.class, () -> queries((uri, cookie) -> envelope(response))
                .rankings("weekly", date.minusDays(1), 2, null)).status);
    }

    @Test void sparseRankingPageOmitsMaskedEntriesAndKeepsPixivPositions() {
        var query = queries((uri, cookie) -> envelope("""
                {"date":"11/20/2026","display_a":{"mode":"daily","page":2,"next":null,"rank_a":{
                  "8":{"rank":59,"id":99,"title":"Last","user_id":3,"user_name":"Author"},
                  "4":{"rank":55,"mask_reason":"login_only"},
                  "0":{"rank":51,"id":88,"title":"First","user_id":3,"user_name":"Author"}
                }}}
                """));
        var result = query.rankings("daily", LocalDate.of(2026, 11, 20), 2, null);
        assertEquals(List.of(51, 59), result.items().stream().map(item -> item.rank()).toList());
    }

    @Test void seriesRequestsOnlyOneChapterPageAndUsesNestedChapterNumbers() {
        var queries = queries((uri, cookie) -> {
            if (uri.getPath().equals("/ajax/novel/series/24")) return envelope(META);
            assertEquals("/ajax/novel/series_content/24", uri.getPath());
            assertEquals("limit=30&last_order=30&order_by=asc", uri.getRawQuery());
            return envelope("{\"page\":{\"seriesContents\":[" + CHAPTER + "]}}");
        });
        var page = queries.series(24, 2, null);
        assertTrue(page.hasNext());
        var chapter = page.items().getFirst();
        assertEquals(31, chapter.order());
        assertEquals(16, chapter.novel().characterCount());
        assertEquals("Artist", chapter.novel().userName());
        assertTrue(page.filter(id -> false).items().isEmpty());
    }

    @Test void emptySeriesPageDoesNotOverflowAndForeignChaptersAreRejected() {
        var calls = new AtomicInteger();
        var queries = queries((uri, cookie) -> {
            calls.incrementAndGet();
            if (uri.getPath().equals("/ajax/novel/series/24")) return envelope(META);
            return envelope("{\"page\":{\"seriesContents\":[" + CHAPTER.replace("\"id\":24", "\"id\":25") + "]}}");
        });
        var beyond = queries.series(24, Integer.MAX_VALUE, null);
        assertFalse(beyond.hasNext());
        assertTrue(beyond.items().isEmpty());
        assertEquals(1, calls.get());
        assertEquals(502, assertThrows(ContentFailure.class, () -> queries.series(24, 1, null)).status);
    }

    private static NovelContentQueries queries(PixivAjaxClient ajax) {
        return new NovelContentQueries(new ContentQueries(ajax, null, new JsonMapper()));
    }

    private static String envelope(String body) {
        return "{\"error\":false,\"body\":" + body + "}";
    }
}
