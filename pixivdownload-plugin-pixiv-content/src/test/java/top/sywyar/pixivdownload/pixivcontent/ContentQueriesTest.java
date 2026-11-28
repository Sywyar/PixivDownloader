package top.sywyar.pixivdownload.pixivcontent;

import java.net.URI;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure;
import top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentials;

import static org.junit.jupiter.api.Assertions.*;

class ContentQueriesTest {
    private static final String CARD = """
            {"id":"91","title":"A","userId":"2","userName":"Artist","pageCount":2,"illustType":1,
            "xRestrict":1,"aiType":2,"createDate":"2026-01-02T09:00:00+09:00"}
            """.trim();
    private static final String PAGE = """
            {"urls":{"regular":"https://i.pximg.net/preview.jpg","original":"https://i.pximg.net/original.png"},
            "width":320,"height":480}
            """.trim();

    @Test void searchEncodesKeywordOnceAndSkipsPixivAdSlots() {
        var queries = ajax((uri, cookie) -> {
            assertEquals("/ajax/search/artworks/%E7%8C%AB%20%2B%2050%25", uri.getRawPath());
            assertTrue(uri.getRawQuery().contains("word=%E7%8C%AB%20%2B%2050%25"));
            assertTrue(uri.getRawQuery().contains("p=2"));
            assertEquals("ticket", cookie);
            return envelope("""
                    {"illustManga":{"data":[{"isAdContainer":true},%s,{"isMasked":true}],
                    "total":335,"lastPage":3}}
                    """.formatted(CARD));
        });
        var result = queries.search("猫 + 50%", "date_d", "safe", "s_tag", ContentModels.ArtworkSearchType.all, null, null, 2, "ticket");
        assertEquals(1, result.items().size());
        assertEquals("/api/pixiv-content/artworks/91/pages/0/image", result.items().getFirst().previewPath());
        assertEquals("manga", result.items().getFirst().type());
        assertEquals(1, result.items().getFirst().xRestrict());
        assertEquals(2, result.items().getFirst().aiType());
        assertEquals(Instant.parse("2026-01-02T00:00:00Z"), result.items().getFirst().createdAt());
        assertNull(result.items().getFirst().uploadedAt());
        assertTrue(result.hasNext());
        assertEquals(335, result.total());
        assertTrue(result.filter(id -> false).items().isEmpty());
    }

    @Test
    @DisplayName("排行榜经宿主 JSON 出口携带凭据读取，并拒绝期号不符的结果")
    void rankingsReadPublicationDateAndPixivRankAndRejectWrongIssue() {
        var queries = new ContentQueries((uri, cookie) -> {
            assertEquals("www.pixiv.net", uri.getHost());
            assertEquals("/ranking.php", uri.getPath());
            assertEquals("mode=weekly&format=json&p=2&date=20261120", uri.getRawQuery());
            assertEquals("ticket", cookie);
            return """
                    {"mode":"weekly","page":2,"date":"20261120","next":false,"contents":[
                    {"rank":58,"illust_id":91,"illust_type":"1","illust_page_count":"2",
                    "title":"A","user_id":2,"user_name":"Artist","illust_upload_timestamp":1500000000}]}
                    """;
        }, new JsonMapper());
        var result = queries.rankings("weekly", ContentModels.RankingContent.all, LocalDate.of(2026, 11, 20), 2, "ticket");
        assertEquals(LocalDate.of(2026, 11, 20), result.rankingDate());
        assertEquals(58, result.items().getFirst().rank());
        var ranked = result.items().getFirst().artwork();
        assertNull(ranked.xRestrict());
        assertNull(ranked.aiType());
        assertNull(ranked.createdAt());
        assertEquals(Instant.ofEpochSecond(1500000000), ranked.uploadedAt());
        assertFalse(result.hasNext());
        var malformed = new ContentQueries((uri, cookie) ->
                "{\"mode\":\"weekly\",\"page\":1,\"date\":\"20260230\",\"contents\":[],\"next\":false}", new JsonMapper());
        assertThrows(ContentFailure.class, () -> malformed.rankings("weekly", ContentModels.RankingContent.all, null, 1, null));
    }

    @Test void eachSearchTypeRequestsItsOwnUpstreamResultSetAndPagination() {
        record Example(ContentModels.ArtworkSearchType type, String path, String filter, String key, int illustType) {}
        for (var sample : List.of(
                new Example(ContentModels.ArtworkSearchType.illustration, "illustrations", "illust", "illust", 0),
                new Example(ContentModels.ArtworkSearchType.manga, "manga", "manga", "manga", 1),
                new Example(ContentModels.ArtworkSearchType.ugoira, "illustrations", "ugoira", "illust", 2))) {
            var queries = ajax((uri, cookie) -> {
                assertEquals("/ajax/search/" + sample.path + "/ink", uri.getPath());
                assertTrue(uri.getQuery().contains("type=" + sample.filter + "&"));
                assertTrue(uri.getQuery().contains("p=3"));
                return envelope("{\"" + sample.key + "\":{\"data\":[" + CARD.replace("\"illustType\":1",
                        "\"illustType\":" + sample.illustType) + "],\"total\":147,\"lastPage\":3}}");
            });
            var result = queries.search("ink", "date_d", "all", "s_tag", sample.type, null, null, 3, null);
            assertEquals(147, result.total());
            assertFalse(result.hasNext());
            assertEquals(sample.type.name(), result.items().getFirst().type());
        }
    }

    @Test void multiCandidateResultsCannotBeReusedByAnonymousOrSingleCredentialRequests() {
        var calls = new AtomicInteger();
        var queries = ajax((uri, cookie) -> {
            calls.incrementAndGet();
            var options = AcquisitionCredentials.candidates(cookie);
            String chosen = options.size() > 1 ? options.getLast() : cookie == null ? "anonymous" : cookie;
            return envelope("[" + PAGE.replace("preview.jpg", chosen + ".jpg") + "]");
        });
        var raw = queries.readImage(91, 0, "preview", "A", URI::getPath);
        assertEquals("/A.jpg", raw);
        var multi = new AcquisitionCredentials(List.of("A", "B"));
        for (int i = 0; i < 2; i++) {
            assertEquals("/B.jpg", multi.call(() -> queries.readImage(91, 0, "preview", "A", URI::getPath)));
        }
        var one = new AcquisitionCredentials(List.of("A"));
        assertEquals("/A.jpg", one.call(() -> queries.readImage(91, 0, "preview", "A", URI::getPath)));
        assertEquals("/A.jpg", one.call(() -> queries.readImage(91, 0, "preview", "A", URI::getPath)));
        assertEquals("/A.jpg", queries.readImage(91, 0, "preview", "A", URI::getPath));
        assertEquals("/B.jpg", queries.readImage(91, 0, "preview", "B", URI::getPath));
        assertEquals("/anonymous.jpg", queries.readImage(91, 0, "preview", null, URI::getPath));
        assertEquals(6, calls.get());
    }

    @Test void observedPermissionLossForgetsTheSuccessfulManifest() {
        var denied = new AtomicBoolean();
        var pages = new AtomicInteger();
        var queries = ajax((uri, cookie) -> {
            if (uri.getPath().endsWith("/pages")) pages.incrementAndGet();
            if (denied.get()) throw new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 403);
            return envelope("[" + PAGE + "]");
        });
        assertEquals("/preview.jpg", queries.readImage(91, 0, "preview", "A", URI::getPath));
        denied.set(true);
        assertThrows(PixivAjaxException.class, () -> queries.artwork(91, "A"));
        assertThrows(PixivAjaxException.class, () -> queries.readImage(91, 0, "preview", "A", URI::getPath));
        assertEquals(2, pages.get());
    }

    @Test void maskedArtworkDetailInvalidatesEarlierPageAccess() {
        var pages = new AtomicInteger();
        var queries = ajax((uri, cookie) -> {
            if (!uri.getPath().endsWith("/pages")) return envelope("{\"id\":91,\"isMasked\":true}");
            if (pages.incrementAndGet() == 1) return envelope("[" + PAGE + "]");
            throw new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 403);
        });
        assertEquals("/preview.jpg", queries.readImage(91, 0, "preview", null, URI::getPath));
        assertEquals(403, assertThrows(ContentFailure.class, () -> queries.artwork(91, null)).status);
        assertThrows(PixivAjaxException.class, () -> queries.readImage(91, 0, "preview", null, URI::getPath));
    }

    @Test void invalidOrIncompleteManifestDoesNotHideARepairOnTheNextRead() {
        var calls = new AtomicInteger();
        var query = ajax((uri, cookie) -> {
            String second = calls.incrementAndGet() == 1
                    ? PAGE.replace("i.pximg.net", "not-pixiv.example") : PAGE;
            return envelope("[" + PAGE + "," + second + "]");
        });
        assertEquals(502, assertThrows(ContentFailure.class,
                () -> query.readImage(91, 0, "preview", null, URI::getPath)).status);
        assertEquals("/preview.jpg", query.readImage(91, 0, "preview", null, URI::getPath));
        assertEquals("/original.png", query.readImage(91, 1, "original", null, URI::getPath));
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("CDN 链接失效时用同一凭据只刷新一次页清单，并记录重试或放弃")
    void staleCdnIsRefetchedOnceWithTheSameCredentialAndOnlyWhenTheUrlChanges() {
        var log = new LogCapture(ContentQueries.class);
        var manifestPath = new AtomicReference<>("first.jpg");
        var pages = new AtomicInteger();
        var transfers = new AtomicInteger();
        var queries = ajax((uri, cookie) -> {
            assertEquals("A", cookie);
            pages.incrementAndGet();
            return envelope("[" + PAGE.replace("preview.jpg", manifestPath.get()) + "]");
        });
        assertEquals("/first.jpg", queries.readImage(91, 0, "preview", "A", URI::getPath));
        manifestPath.set("second.jpg");
        assertEquals("/second.jpg", queries.readImage(91, 0, "preview", "A", uri -> {
            transfers.incrementAndGet();
            if (uri.getPath().equals("/first.jpg")) throw ContentFailure.upstream(404);
            return uri.getPath();
        }));
        assertEquals(2, transfers.get());
        assertEquals(2, pages.get());
        assertEquals(403, assertThrows(ContentFailure.class, () -> queries.readImage(91, 0, "preview", "A", uri -> {
            transfers.incrementAndGet();
            throw ContentFailure.upstream(403);
        })).status);
        assertEquals(3, transfers.get());
        assertEquals(3, pages.get());
        manifestPath.set("third.jpg");
        assertEquals("/third.jpg", queries.readImage(91, 0, "preview", "A", URI::getPath));
        log.close();
        var events = log.messages(Level.INFO);
        assertEquals(2, events.size());
        assertTrue(events.get(0).contains("artworkId=91 pageIndex=0 upstreamStatus=404 action=retry"), events.get(0));
        assertTrue(events.get(1).contains("upstreamStatus=403 action=give_up"), events.get(1));
        assertTrue(events.stream().noneMatch(text -> text.contains("pximg")), events.toString());
    }

    @Test
    @DisplayName("校验失败与映射自宿主端口的失败都能定位到插件内的源码位置")
    void failuresReportWhereInThePluginTheyWereRaised() {
        var mismatch = ajax((uri, cookie) -> envelope("{\"userId\":\"3\",\"name\":\"A\"}"));
        var invalid = assertThrows(ContentFailure.class, () -> mismatch.user(2, null));
        assertTrue(invalid.origin().startsWith("ContentQueries.user:"), invalid.origin());
        var denied = ajax((uri, cookie) -> { throw new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 403); });
        var mapped = ContentFailure.from(assertThrows(PixivAjaxException.class, () -> denied.user(2, null)));
        assertTrue(mapped.origin().contains("ContentQueries.user:"), mapped.origin());
        assertEquals("unknown", ContentFailure.origin(new IllegalStateException("outside the plugin") {
            @Override public synchronized Throwable fillInStackTrace() { return this; }
        }));
    }

    @Test void artworkPublishesOnlySameOriginPageLinksAndResolvesExactPageVariant() {
        var pageReads = new AtomicInteger();
        var queries = ajax((uri, cookie) -> switch (uri.getPath()) {
            case "/ajax/illust/91" -> envelope("""
                    {"id":"91","title":"A","userId":"2","userName":"Artist","illustType":1,"pageCount":2,
                    "tags":{"tags":[{"tag":"ink"}]},"seriesNavData":{"seriesId":"8","title":"Book","order":3},
                    "description":"ink study","bookmarkCount":12,"xRestrict":0,"aiType":1,
                    "createDate":"2026-01-02T00:00:00Z","uploadDate":"2026-01-03T00:00:00Z"}
                    """);
            case "/ajax/illust/91/pages" -> {
                pageReads.incrementAndGet();
                yield envelope("[" + PAGE + "," + PAGE.replace("original.png","second.png").replace("480","960") + "]");
            }
            default -> throw new AssertionError(uri);
        });
        var work = queries.artwork(91, null);
        assertEquals(8L, work.series().id());
        assertEquals(List.of("ink"), work.tags());
        assertEquals("ink study", work.description());
        assertEquals(12, work.bookmarkCount());
        assertEquals(Instant.parse("2026-01-03T00:00:00Z"), work.uploadedAt());
        assertEquals(320, work.pages().get(0).width());
        assertEquals(960, work.pages().get(1).height());
        assertEquals("/api/pixiv-content/artworks/91/pages/1/image?size=original", work.pages().get(1).originalPath());
        assertEquals(URI.create("https://i.pximg.net/second.png"), queries.readImage(91, 1, "original", null, uri -> uri));
        assertEquals(URI.create("https://i.pximg.net/preview.jpg"), queries.readImage(91, 0, "preview", null, uri -> uri));
        assertEquals(1, pageReads.get());
        assertEquals(404, assertThrows(ContentFailure.class, () -> queries.readImage(91, 2, "original", null, uri -> uri)).status);
        var foreign = ajax((uri, cookie) -> envelope("[" + PAGE.replace("i.pximg.net", "i.pximg.net.example.org") + "]"));
        assertEquals(502, assertThrows(ContentFailure.class, () -> foreign.readImage(91, 0, "original", null, uri -> uri)).status);
    }

    @Test void artistPagesJoinOnlySelectedCardsAndRetainNumericOrder() {
        var calls = new AtomicInteger();
        var queries = ajax((uri, cookie) -> {
            calls.incrementAndGet();
            return switch (uri.getPath()) {
                case "/ajax/user/2" -> envelope("""
                        {"userId":"2","name":"Artist","comment":"Hello","imageBig":"https://i.pximg.net/avatar.jpg"}
                        """);
                case "/ajax/user/2/profile/all" -> envelope("""
                        {"illusts":{"9":null,"91":null},"manga":{"17":null}}
                        """);
                case "/ajax/user/2/illusts" -> {
                    assertEquals("ids%5B%5D=91&ids%5B%5D=17&ids%5B%5D=9", uri.getRawQuery());
                    yield envelope("{\"9\":" + CARD.replace("\"91\"", "\"9\"") + ",\"91\":" + CARD + "}");
                }
                default -> throw new AssertionError(uri);
            };
        });
        assertEquals("Hello", queries.user(2, null).description());
        var works = queries.userArtworks(2, 1, null);
        assertEquals(List.of(91L, 9L), works.items().stream().map(ContentModels.Card::id).toList());
        assertEquals(3, works.total());
        assertTrue(queries.userArtworks(2, Integer.MAX_VALUE, null).items().isEmpty());
        assertEquals(4, calls.get());
    }

    @Test void seriesUsesRequestedSeriesAndAuthoritativeChapterMembership() {
        var queries = ajax((uri, cookie) -> envelope("""
                {"illustSeries":[{"id":"999"},{"id":"8","title":"Book","userId":"2","total":18}],
                 "page":{"seriesId":8,"series":[{"workId":"91","order":17},{"workId":"92","order":16}]},
                 "thumbnails":{"illust":[%s,%s,%s]}}
                """.formatted(CARD.replace("\"91\"", "\"3\""), CARD.replace("\"91\"", "\"92\""), CARD)));
        var result = queries.series(8, 1, null);
        assertEquals(List.of(17,16), result.items().stream().map(ContentModels.SeriesEntry::order).toList());
        assertEquals(List.of(91L,92L), result.items().stream().map(entry -> entry.artwork().id()).toList());
        assertTrue(result.hasNext());
    }

    @Test void malformedUpstreamCannotFabricateResultsOrAllocateFromUnverifiedPageCount() {
        var broken = ajax((uri, cookie) -> "not JSON");
        assertEquals(502, assertThrows(ContentFailure.class, () -> broken.user(2, null)).status);
        var mismatch = ajax((uri, cookie) -> uri.getPath().endsWith("/pages") ? envelope("[]")
                : envelope("{\"id\":91,\"pageCount\":2147483647}"));
        assertEquals(502, assertThrows(ContentFailure.class, () -> mismatch.artwork(91, null)).status);
    }

    private static String envelope(String body) {
        return "{\"error\":false,\"body\":" + body + "}";
    }

    private static ContentQueries ajax(PixivAjaxClient ajax) {
        return new ContentQueries(ajax, new JsonMapper());
    }
}
