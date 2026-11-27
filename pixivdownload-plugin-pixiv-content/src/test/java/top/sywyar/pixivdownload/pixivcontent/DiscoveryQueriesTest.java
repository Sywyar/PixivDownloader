package top.sywyar.pixivdownload.pixivcontent;

import java.time.LocalDate;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.pixivcontent.ContentModels.RankingContent;
import top.sywyar.pixivdownload.pixivcontent.ContentModels.SeriesType;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryQueriesTest {
    private static ContentQueries queries(PixivAjaxClient ajax) {
        return new ContentQueries(ajax, null, new JsonMapper());
    }

    @Test void mapsOnlyRequestedSeriesPageAndWithholdsUnscopedCover() {
        String row = "{\"id\":71,\"userId\":8,\"title\":\"Story\",\"url\":\"https://i.pximg.net/cover.jpg\"}";
        String rows = String.join(",", Collections.nCopies(24, row)) + "," + row.replace("71", "99");
        var discovery = new DiscoveryQueries(queries((uri, cookie) -> "{\"error\":false,\"body\":{\"mangaSeries\":[" + rows + "]}}"));
        var page = discovery.userSeries(8, SeriesType.manga, 2, "cookie", false);
        assertEquals(25, page.total());
        assertFalse(page.hasNext());
        assertEquals(1, page.items().size());
        assertEquals("/api/pixiv-content/series/99", page.items().getFirst().detailPath());
        assertNull(page.items().getFirst().coverUrl());
        assertTrue(discovery.userSeries(8, SeriesType.manga, Integer.MAX_VALUE, null, true).items().isEmpty());
        assertThrows(ContentFailure.class, () -> discovery.userSeries(7, SeriesType.manga, 1, null, true));
    }

    @Test void exactTagAndInclusiveDateRangeReachBothSearches() {
        var content = queries((uri, cookie) -> {
            assertTrue(uri.getRawQuery().contains("s_mode=s_tag_full"));
            assertTrue(uri.getRawQuery().contains("scd=2026-10-31"));
            assertTrue(uri.getRawQuery().contains("ecd=2026-10-31"));
            String group = uri.getPath().contains("/novels/") ? "novel" : "illustManga";
            return "{\"error\":false,\"body\":{\"" + group + "\":{\"data\":[],\"lastPage\":1,\"total\":0}}}";
        });
        var date = LocalDate.of(2026, 10, 31);
        assertTrue(content.search("cat", "date", "all", "s_tag_full", ContentModels.ArtworkSearchType.all,
                date, date, 1, null).items().isEmpty());
        assertTrue(new NovelContentQueries(content).search("cat", "date", "all", "s_tag_full", date, date, 1, null).items().isEmpty());
    }

    @Test void selectedRankingContentMustMatchUpstream() {
        var content = new ContentQueries(null, (uri, cookie) -> {
            assertTrue(uri.getQuery().contains("content=manga"));
            return "{\"mode\":\"rookie\",\"content\":\"illust\",\"page\":1,\"date\":\"20261120\",\"contents\":[],\"next\":false}";
        }, new JsonMapper());
        assertThrows(ContentFailure.class, () -> content.rankings("rookie", RankingContent.manga, null, 1, null));
    }
}
