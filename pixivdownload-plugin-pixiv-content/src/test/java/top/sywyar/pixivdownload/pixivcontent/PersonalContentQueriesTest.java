package top.sywyar.pixivdownload.pixivcontent;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentials;

import static org.junit.jupiter.api.Assertions.*;

class PersonalContentQueriesTest {
    private static final String COOKIE = "PHPSESSID=42_fixture";
    private static final String SELF = "{\"userData\":{\"id\":\"42\"}}";
    private static final String ART = """
            {"id":"91","title":"A","userId":"7","userName":"Artist","illustType":2,"pageCount":1,
            "bookmarkCount":17,"tags":["ink"],"xRestrict":1,"aiType":2,"createDate":"2026-09-19T02:03:04Z"}
            """.trim();
    private static final String NOVEL = """
            {"id":"8","title":"N","userId":"7","userName":"Author","textCount":350,"description":"Opening",
            "url":"https://i.pximg.net/novel-cover.jpg","tags":["fantasy"]}
            """.trim();

    @Test void bookmarksPinTheSelectedCredentialAndEncodePrivateTagAndPageExactlyOnce() {
        var selected = new AcquisitionCredentials(List.of(COOKIE, "PHPSESSID=74_other", "PHPSESSID=42_alternate"));
        var calls = new AtomicInteger();
        var queries = queries((uri, cookie) -> {
            assertEquals("www.pixiv.net", uri.getHost());
            assertEquals(COOKIE, cookie);
            assertEquals(List.of(COOKIE), AcquisitionCredentials.candidates(cookie));
            calls.incrementAndGet();
            return switch (uri.getPath()) {
                case "/ajax/user/self" -> SELF;
                case "/ajax/user/42/illusts/bookmarks" -> {
                    assertEquals("rest=hide&tag=%E7%8C%AB%2B%25%26&offset=48&limit=48", uri.getRawQuery());
                    yield envelope("""
                            {"total":97,"works":[{"isMasked":true},{"id":16,"isBookmarkable":false},%s]}
                            """.formatted(ART));
                }
                default -> throw new AssertionError(uri);
            };
        });
        var result = selected.call(() -> queries.bookmarks("artwork", "hide", "猫+%&", 2, COOKIE));
        assertEquals(2, calls.get());
        assertEquals(97, result.total());
        assertTrue(result.hasNext());
        var card = result.items().getFirst();
        assertEquals("ugoira", card.type());
        assertEquals("/api/pixiv-content/artworks/91/pages/0/image", card.previewPath());
        assertEquals(17, card.bookmarkCount());
        assertEquals(List.of("ink"), card.tags());
        assertEquals(2, card.aiType());
        assertEquals(1, card.xRestrict());
        assertEquals(java.time.Instant.parse("2026-09-19T02:03:04Z"), card.createdAt());
        assertNull(card.characterCount());
        assertTrue(result.filter(id -> false).items().isEmpty());
    }

    @Test void novelBookmarksReturnNovelMetadataAndUseTheirOwnPageSize() {
        var queries = queries((uri, cookie) -> {
            if ("/ajax/user/self".equals(uri.getPath())) return SELF;
            assertEquals("/ajax/user/42/novels/bookmarks", uri.getPath());
            assertEquals("rest=show&tag=&offset=24&limit=24", uri.getRawQuery());
            return envelope("{\"total\":48,\"works\":[" + NOVEL + "]}");
        });
        var result = queries.bookmarks("novel", "show", null, 2, COOKIE);
        var card = result.items().getFirst();
        assertEquals("novel", result.type());
        assertEquals(350, card.characterCount());
        assertEquals("/api/pixiv-content/novels/8", card.detailPath());
        assertEquals("/api/pixiv-content/novels/8/cover", card.coverPath());
        assertEquals(List.of("fantasy"), card.tags());
        assertEquals("Opening", card.description());
        assertNull(card.previewPath());
        assertFalse(result.hasNext());
    }

    @ParameterizedTest
    @ValueSource(strings = {"artwork", "novel"})
    void feedUsesTheSignedInAccountAndUpstreamPageOrder(String type) {
        boolean novel = "novel".equals(type);
        String category = novel ? "novel" : "illust";
        String first = novel ? NOVEL : ART;
        String id = novel ? "8" : "91";
        var queries = queries((uri, cookie) -> {
            assertEquals(COOKIE, cookie);
            if ("/ajax/user/self".equals(uri.getPath())) return SELF;
            assertEquals("/ajax/follow_latest/" + category, uri.getPath());
            assertEquals("mode=all&p=3", uri.getRawQuery());
            return envelope("""
                    {"page":{"ids":["19","%s","58"],"isLastPage":false},"thumbnails":{"%s":[%s,%s,
                    {"id":"58","isMasked":true},%s]}}
                    """.formatted(id, category, first, first.replace("\"" + id + "\"", "\"19\""),
                    first.replace("\"" + id + "\"", "\"27\"")));
        });
        var result = queries.followLatest(type, 3, COOKIE);
        assertEquals(List.of(19L, Long.valueOf(id)), result.items().stream().map(card -> card.id()).toList());
        assertTrue(result.hasNext());
        assertNull(result.total());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"tag=example", "PHPSESSID=42_", "PHPSESSID=42_a; PHPSESSID=99_b",
            "PHPSESSID=42_a; phpsessid=42_a", "PHPSESSID=42_a\r\nx-request: invalid"})
    void absentOrAmbiguousSessionIsRejectedBeforeAnyUpstreamCall(String cookie) {
        var queries = queries((uri, header) -> { throw new AssertionError(uri); });
        var failure = assertThrows(ContentFailure.class, () -> queries.bookmarks("artwork", "show", null, 1, cookie));
        assertEquals("ACQUISITION_CREDENTIAL_REQUIRED", failure.code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"userData\":null}", "{\"userData\":{\"id\":\"74\"}}"})
    void anonymousOrMismatchedAccountNeverReadsEvenPublicBookmarks(String self) {
        var calls = new AtomicInteger();
        var queries = queries((uri, cookie) -> {
            assertEquals("/ajax/user/self", uri.getPath());
            calls.incrementAndGet();
            return self;
        });
        assertEquals(403, assertThrows(ContentFailure.class,
                () -> queries.bookmarks("artwork", "show", null, 1, COOKIE)).status);
        assertEquals(403, assertThrows(ContentFailure.class, () -> queries.followLatest("novel", 1, COOKIE)).status);
        assertEquals(2, calls.get());
    }

    @Test void pinnedSessionRetainsScopedCancellationAndDoesNotBorrowAnotherPrimary() {
        var cancelled = new AtomicBoolean();
        var scope = new AcquisitionCredentials(List.of(COOKIE, "PHPSESSID=99_other")).withCancellation(cancelled::get);
        var queries = queries((uri, cookie) -> {
            AcquisitionCredentials.current().checkCancelled();
            assertEquals("/ajax/user/self", uri.getPath());
            cancelled.set(true);
            return SELF;
        });
        scope.run(() -> {
            assertEquals(400, assertThrows(ContentFailure.class,
                    () -> queries.followLatest("artwork", 1, "PHPSESSID=99_other")).status);
            assertThrows(CancellationException.class, () -> queries.followLatest("artwork", 1, COOKIE));
            assertSame(scope, AcquisitionCredentials.current());
        });
    }

    @Test void followingUsesTheVerifiedAccountAndSkipsUpstreamPagesBy24() {
        var queries = queries((uri, cookie) -> {
            assertEquals(COOKIE, cookie);
            if ("/ajax/user/self".equals(uri.getPath())) return SELF;
            assertEquals("/ajax/user/42/following", uri.getPath());
            assertEquals("offset=24&limit=24&rest=hide&tag=&acceptingRequests=0", uri.getRawQuery());
            return envelope("""
                    {"total":26,"users":[{"userId":"7","userName":"Artist","userComment":"About me",
                    "profileImageUrl":"https://i.pximg.net/user-profile/img.jpg"}]}
                    """);
        });
        var result = queries.following("hide", 2, COOKIE);
        assertEquals(26, result.total());
        assertEquals("About me", result.items().getFirst().description());
        assertFalse(result.hasNext());
    }

    @Test void returnsOnlySelectedCollectionPageAndCanOmitUnattributedCoverImages() {
        String longId = "123456789012345678901";
        String ids = IntStream.rangeClosed(1, 48).mapToObj(Integer::toString).collect(Collectors.joining(","));
        var queries = queries((uri, cookie) -> switch (uri.getPath()) {
            case "/ajax/user/self" -> SELF;
            case "/ajax/user/42/profile/all" -> envelope("{\"collectionIds\":[" + ids + ",\"" + longId + "\",50]}");
            case "/ajax/user/42/profile/collections" -> {
                assertEquals("ids%5B%5D=" + longId + "&ids%5B%5D=50", uri.getRawQuery());
                yield envelope("""
                        {"works":[{"id":50,"title":"Second"},{"id":"%s","title":"First","tags":["winter"],
                        "thumbnailImageUrl":"https://i.pximg.net/cover.jpg","caption":"Seasonal works"}]}
                        """.formatted(longId));
            }
            default -> throw new AssertionError(uri);
        });
        var result = queries.collections(2, COOKIE);
        assertEquals(50, result.total());
        assertEquals(List.of(longId, "50"), result.items().stream().map(item -> item.id()).toList());
        assertFalse(result.hasNext());
        assertEquals("/api/pixiv-content/me/collections/" + longId + "/works", result.items().getFirst().worksPath());
        assertEquals("https://i.pximg.net/cover.jpg", result.items().getFirst().coverUrl());
        assertNull(result.withoutCovers().items().getFirst().coverUrl());
    }

    @Test void collectionWorksNeverFetchesAnotherAccountsCollectionEvenWhenTheIdIsValid() {
        var selected = new AcquisitionCredentials(List.of(COOKIE, "PHPSESSID=74_other"));
        var queries = queries((uri, cookie) -> {
            assertEquals(List.of(COOKIE), AcquisitionCredentials.candidates(cookie));
            return switch (uri.getPath()) {
                case "/ajax/user/self" -> SELF;
                case "/ajax/user/42/profile/all" -> envelope("{\"collections\":{\"9123\":null}}");
                default -> throw new AssertionError(uri);
            };
        });
        var denied = selected.call(() -> assertThrows(ContentFailure.class, () -> queries.collectionWorks("74", 1, COOKIE)));
        assertEquals(404, denied.status);
        assertThrows(top.sywyar.pixivdownload.core.web.HttpParameterException.class,
                () -> queries.collectionWorks("9/13", 1, null));
    }

    @Test void mixedCollectionPaginatesInTileOrderAndChecksEachNamespaceIndependentOfId() {
        String artwork = ART.replace("\"91\"", "\"8\"");
        String tile = "{\"type\":\"Work\",\"status\":\"Active\",\"workType\":\"illust\",\"workId\":\"8\"}";
        String novelTile = tile.replace("illust", "novel");
        String prefix = String.join(",", java.util.Collections.nCopies(48, tile));
        var queries = queries((uri, cookie) -> switch (uri.getPath()) {
            case "/ajax/user/self" -> SELF;
            case "/ajax/user/42/profile/all" -> envelope("{\"collectionIds\":[\"999999999999999999999\"]}");
            case "/ajax/collection/999999999999999999999" -> envelope("""
                    {"data":{"detail":{"tiles":[%s,%s,%s,%s,{"type":"Header"}]}},
                    "thumbnails":{"illust":[%s],"novel":[%s]}}
                    """.formatted(prefix, tile.replace("Active", "Hidden"), novelTile, tile, artwork, NOVEL));
            default -> throw new AssertionError(uri);
        });
        var result = queries.collectionWorks("999999999999999999999", 2, COOKIE);
        assertEquals(50, result.total());
        assertFalse(result.hasNext());
        assertEquals(List.of("novel", "ugoira"), result.items().stream().map(item -> item.type()).toList());
        assertEquals("novel", result.filter(id -> false, id -> id == 8).items().getFirst().type());
        assertEquals("ugoira", result.filter(id -> id == 8, id -> false).items().getFirst().type());
    }

    private static PersonalContentQueries queries(PixivAjaxClient ajax) {
        return new PersonalContentQueries(new ContentQueries(ajax, new JsonMapper()));
    }

    private static String envelope(String body) {
        return "{\"error\":false,\"body\":" + body + "}";
    }
}
