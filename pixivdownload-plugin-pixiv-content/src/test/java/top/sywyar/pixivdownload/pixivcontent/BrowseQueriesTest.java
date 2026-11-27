package top.sywyar.pixivdownload.pixivcontent;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.web.HttpParameterException;

import static org.junit.jupiter.api.Assertions.*;

class BrowseQueriesTest {
    private static final String SESSION = "PHPSESSID=42_test";

    private static ContentQueries queries(PixivAjaxClient ajax) {
        return new ContentQueries(ajax, null, new JsonMapper());
    }

    private static String body(String json) { return "{\"error\":false,\"body\":" + json + "}"; }

    @Test void continuationUsesOnlyTheNextSeedIdsAndPreservesOrderAndVisibility() {
        var discovery = new DiscoveryQueries(queries((uri, cookie) -> {
            if (uri.getPath().endsWith("/init"))
                return body("{\"novels\":[],\"nextIds\":[8,9,10]}");
            assertEquals("novelIds[]=8&novelIds[]=9", uri.getQuery());
            return body("""
                    {"novels":[{"id":9,"title":"B","userId":5,"userName":"U"},{"id":8,"title":"A","userId":5,"userName":"U"}]}
                    """);
        }));
        var cursor = discovery.relatedNovels(80, 2, null, null, id -> true).nextCursor();
        assertNotNull(cursor);
        var second = discovery.relatedNovels(80, 2, cursor, null, id -> id != 9);
        assertNotNull(second.nextCursor());
        assertEquals(second, discovery.relatedNovels(80, 2, cursor, null, id -> id != 9));
        assertEquals(8, second.items().getFirst().id());
        assertThrows(HttpParameterException.class, () -> discovery.relatedNovels(81, 2, cursor, null, id -> true));
        assertThrows(HttpParameterException.class, () -> discovery.relatedNovels(80, 2, cursor, SESSION, id -> true));
    }

    @Test void recommendationExpiryAndCapacityRequireRestartingFromTheSeed() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var cache = new RecommendationPages(clock::get);
        var cursor = cache.save(7, false, 1, SESSION, new long[]{10, 9});
        var second = cache.read(cursor, 7, false, 1, SESSION).nextCursor();
        assertNull(cache.read(second, 7, false, 1, SESSION).nextCursor());
        clock.set(RecommendationPages.LIFETIME);
        assertEquals(410, assertThrows(ContentFailure.class, () -> cache.read(cursor, 7, false, 1, SESSION)).status);
        String first = cache.save(7, false, 1, null, new long[]{8});
        for (int i = 0; i < RecommendationPages.MAX_SEEDS; i++) cache.save(i + 8, false, 1, null, new long[]{9});
        assertEquals(410, assertThrows(ContentFailure.class, () -> cache.read(first, 7, false, 1, null)).status);
    }

    @Test void repliesMustBelongToTheVisibleWorkAndTheRequestedParent() {
        var requests = new AtomicInteger();
        var discovery = new DiscoveryQueries(queries((uri, cookie) -> {
            requests.incrementAndGet();
            if (uri.getPath().endsWith("roots")) {
                assertEquals("novel_id=7&offset=20&limit=20", uri.getQuery());
                return body("""
                        {"hasNext":false,"comments":[{"id":15,"userId":3,"userName":"A","commentDate":"2026-10-04","hasReplies":true}]}
                        """);
            }
            assertEquals("comment_id=15&page=2", uri.getQuery());
            return body("""
                    {"hasNext":false,"comments":[{"id":18,"commentRootId":14,"userId":2,"userName":"B","commentDate":"2026-10-05"}]}
                    """);
        }));
        assertEquals(404, assertThrows(ContentFailure.class, () -> discovery.replies(7, true, 88, 2, 2, null)).status);
        assertEquals(1, requests.get());
        assertEquals("PIXIV_RESPONSE_INVALID",
                assertThrows(ContentFailure.class, () -> discovery.replies(7, true, 15, 2, 2, null)).code);
    }

    @Test void authorTagsIntersectAcrossUpstreamPagesBeforePaginating() {
        var offsets = new java.util.ArrayList<String>();
        var content = queries((uri, cookie) -> {
            assertEquals(SESSION, cookie);
            assertEquals("/ajax/user/3/novels/tag", uri.getPath());
            var query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
            String tag = query.getFirst("tag");
            int offset = Integer.parseInt(query.getFirst("offset"));
            offsets.add(tag + ":" + offset);
            int total = tag.equals("common") ? 130 : 52;
            String rows = java.util.stream.IntStream.range(offset, Math.min(total, offset + 48))
                    .mapToObj(i -> "{\"id\":" + (200 - i) + ",\"userId\":3,\"title\":\"N\",\"userName\":\"U\",\"tags\":"
                            + (i % 2 == 0 ? "[\"rare\",\"common\"]" : "[\"rare\"]") + "}")
                    .collect(java.util.stream.Collectors.joining(","));
            return body("{\"total\":" + total + ",\"works\":[" + rows + "]}");
        });
        var novels = new NovelContentQueries(content);
        var page = novels.userNovels(3, 2, SESSION, "ＣＯＭＭＯＮ", "Rare", "common");
        assertEquals(List.of("common:0", "rare:0", "rare:48"), offsets);
        assertEquals(26, page.total());
        assertEquals(List.of(152L, 150L), page.items().stream().map(NovelContentModels.NovelCard::id).toList());
        assertFalse(page.hasNext());
        assertTrue(novels.userNovels(3, 1, SESSION, "rare", "common").hasNext());
    }

    @Test void taggedListsRejectForeignOwnersAndOverbroadIntersections() {
        var foreign = queries((uri, cookie) -> body("{\"total\":1,\"works\":[{\"userId\":4}]}"));
        assertEquals(502, assertThrows(ContentFailure.class, () -> foreign.userArtworks(3, 1, null, "ink")).status);
        var many = queries((uri, cookie) -> body("{\"total\":3000,\"works\":[" +
                String.join(",", java.util.Collections.nCopies(48, "{}")) + "]}"));
        assertEquals("PIXIV_TAG_SEARCH_TOO_BROAD", assertThrows(ContentFailure.class,
                () -> many.userArtworks(3, 1, null, "a", "b")).code);
        assertThrows(HttpParameterException.class, () -> many.userArtworks(3, 1, null, " "));
        assertThrows(HttpParameterException.class, () -> many.userArtworks(3, 1, null, "a b"));
        var tags = java.util.stream.IntStream.range(0, 11).mapToObj(Integer::toString).toArray(String[]::new);
        assertThrows(HttpParameterException.class, () -> many.userArtworks(3, 1, null, tags));
    }

    @Test void userSearchOrdersKeyedProfilesByPageIds() {
        var browse = new BrowseQueries(queries((uri, cookie) -> body("""
                {"page":{"userIds":["8","3"],"total":12},"users":{"3":{"userId":3,"name":"A"},"8":{"id":"8","userName":"B"}}}
                """)));
        assertEquals(List.of("B","A"), browse.users("ink", 1, false, SESSION).items().stream()
                .map(ContentModels.User::name).toList());
    }

    @Test void publicCollectionHidesPrivateMetadataEvenFromItsOwner() {
        var personal = new PersonalContentQueries(queries((uri, cookie) -> body("""
                {"data":{"userCollections":{"80":{"id":"80","status":"private","visibilityScope":1}}}}
                """)));
        assertEquals(404, assertThrows(ContentFailure.class, () -> personal.readCollection("80", 1, SESSION, true)).status);
    }

    @Test void searchRangesUsePixivUnitsAndValidateBoundsAndLanguage() {
        var filters = new SearchFilters(10, 300, 450, 750, 120, 500, SearchFilters.AspectRatio.portrait, 50, 5000, "zh-CN");
        var request = UriComponentsBuilder.fromUriString(ContentQueries.WEB + "/ajax/search/novels");
        filters.apply(request);
        var params = request.build().getQueryParams().toSingleValueMap();
        assertEquals("10", params.get("blt")); assertEquals("300", params.get("bgt"));
        assertEquals("450", params.get("wlt")); assertEquals("750", params.get("wgt"));
        assertEquals("120", params.get("hlt")); assertEquals("500", params.get("hgt"));
        assertEquals("-0.5", params.get("ratio")); assertEquals("zh-cn", params.get("work_lang"));
        assertEquals("50", params.get("tlt")); assertEquals("5000", params.get("tgt"));
        assertThrows(HttpParameterException.class, () -> new SearchFilters(null,null,700,50,null,null,null,null,null,null).apply(request));
        assertThrows(HttpParameterException.class, () -> new SearchFilters(null,null,null,null,null,null,null,null,null,"en&gs=1").apply(request));
    }

    @Test void nonPremiumSessionCannotSilentlyGetAnUnfilteredPage() {
        var personal = new PersonalContentQueries(queries((uri, cookie) -> switch (uri.getPath()) {
            case "/ajax/user/self" -> "{\"userData\":{\"id\":42}}";
            case "/ajax/user/42" -> body("{\"userId\":\"42\",\"premium\":false}");
            default -> throw new AssertionError(uri);
        }));
        var filters = new SearchFilters(100,null,null,null,null,null,null,null,null,null);
        var error = assertThrows(ContentFailure.class, () -> personal.search(SESSION, filters, () -> fail("search ran")));
        assertEquals("PIXIV_PREMIUM_REQUIRED", error.code);
    }

    @Test void tagsDeduplicateCandidatesAndKeepTheKnownEnglishTranslation() {
        var browse = new BrowseQueries(queries((uri, cookie) -> {
            assertNull(cookie);
            return body("{\"relatedTags\":[\"ink\",\"paper\"],\"tagTranslation\":{\"paper\":{\"en\":\"Paper\"}}}");
        }));
        var candidates = new ContentModels.TagSuggestions(List.of(new ContentModels.TagSuggestion("ink","Ink")));
        var first = browse.tags("ink", 1, candidates);
        assertEquals(List.of("Ink", "Paper"), first.items().stream().map(ContentModels.TagSuggestion::translation).toList());
        assertTrue(browse.tags("ink", Integer.MAX_VALUE, candidates).items().isEmpty());
    }
}
