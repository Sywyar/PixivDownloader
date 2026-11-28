package top.sywyar.pixivdownload.pixivcontent;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.function.LongPredicate;
import tools.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.core.web.HttpParameterException;

import static top.sywyar.pixivdownload.pixivcontent.ContentModels.*;
import static top.sywyar.pixivdownload.pixivcontent.ContentQueries.*;

/** Read-only discovery and discussion endpoints on Pixiv's website. */
final class DiscoveryQueries {
    private static final int SERIES_PER_PAGE = 24;
    private static final int COMMENTS_PER_PAGE = 20;
    private final ContentQueries content;
    private final RecommendationPages pages = new RecommendationPages(System::nanoTime);

    DiscoveryQueries(ContentQueries content) {
        this.content = content;
    }

    RelatedArtworks related(long workId, int limit, String cursor, String cookie, LongPredicate visible) {
        var result = recommendations(workId, limit, cursor, cookie, false);
        var items = result.rows().stream().filter(PersonalContentQueries::available)
                .map(row -> card(row, false)).filter(work -> visible.test(work.id())).toList();
        return new RelatedArtworks(items, result.nextCursor());
    }

    RelatedNovels relatedNovels(long workId, int limit, String cursor, String cookie, LongPredicate visible) {
        var result = recommendations(workId, limit, cursor, cookie, true);
        var items = result.rows().stream().filter(PersonalContentQueries::available)
                .map(NovelContentQueries::card).filter(work -> visible.test(work.id())).toList();
        return new RelatedNovels(items, result.nextCursor());
    }

    private record RecommendationRows(List<JsonNode> rows, String nextCursor) {}

    private RecommendationRows recommendations(long workId, int limit, String cursor, String cookie, boolean novel) {
        if (limit < 1 || limit > 48) throw new HttpParameterException("query", "limit", "out_of_range");
        String kind = novel ? "novel" : "illust";
        String list = kind + "s";
        String base = WEB + "/ajax/" + kind;
        if (cursor == null) {
            var first = content.body(URI.create(base + "/" + workId + "/recommend/init?limit=" + limit), cookie);
            var ids = first.path("nextIds");
            if (ids.size() > RecommendationPages.MAX_IDS) throw ContentFailure.tooLarge();
            long[] remaining = ids.isMissingNode() ? new long[0]
                    : array(ids).valueStream().mapToLong(ContentQueries::id).toArray();
            return new RecommendationRows(array(first.path(list)).valueStream().limit(limit).toList(),
                    pages.save(workId, novel, limit, cookie, remaining));
        }
        var batch = pages.read(cursor, workId, novel, limit, cookie);
        var request = UriComponentsBuilder.fromUriString(base + "/recommend/" + list);
        batch.ids().forEach(id -> request.queryParam(novel ? "novelIds[]" : "illust_ids[]", id));
        var fetched = PersonalContentQueries.thumbnails(content.body(request.build().encode().toUri(), cookie).path(list));
        return new RecommendationRows(batch.ids().stream().filter(fetched::containsKey).map(fetched::get).toList(),
                batch.nextCursor());
    }

    UserSeries userSeries(long userId, SeriesType type, int page, String cookie, boolean includeCover) {
        var profile = content.body(URI.create(WEB + "/ajax/user/" + userId + "/profile/all"), cookie);
        var rows = array(profile.path(type == SeriesType.manga ? "mangaSeries" : "novelSeries"));
        long offset = (page - 1L) * SERIES_PER_PAGE;
        var items = new ArrayList<SeriesSummary>();
        for (long index = offset; index < Math.min(offset + SERIES_PER_PAGE, rows.size()); index++) {
            var row = rows.get((int) index);
            if (row.path("isMasked").asBoolean(false)) continue;
            if (id(row.path("userId")) != userId) throw ContentFailure.invalidResponse();
            long seriesId = id(row.path("id"));
            String cover = type == SeriesType.manga ? row.path("url").asString("") :
                    row.path("cover").path("urls").path("240mw").asString("");
            String path = type == SeriesType.manga ? "/series/" : "/novel-series/";
            items.add(new SeriesSummary(seriesId, text(row, "title"),
                    includeCover && !cover.isBlank() ? cdnUri(cover).toString() : null, PixivContentPlugin.API + path + seriesId));
        }
        return new UserSeries(type, page, rows.size(), offset + SERIES_PER_PAGE < rows.size(), items);
    }

    TagSuggestions suggestions(String word) {
        // 标签建议始终匿名请求，关键词只编码一次。
        URI uri = UriComponentsBuilder.fromUriString(WEB + "/rpc/cps.php")
                .queryParam("keyword", "{word}").queryParam("lang", "en").encode().buildAndExpand(word).toUri();
        var items = new ArrayList<TagSuggestion>();
        for (var row : array(content.document(uri, null).path("candidates"))) {
            items.add(new TagSuggestion(text(row, "tag_name"), row.path("tag_translation").asString(null)));
        }
        return new TagSuggestions(items);
    }

    Comments comments(long workId, boolean novel, int page, String cookie) {
        String kind = novel ? "novel" : "illust";
        long offset = (page - 1L) * COMMENTS_PER_PAGE;
        var uri = URI.create(WEB + "/ajax/" + kind + "s/comments/roots?" + kind + "_id=" + workId
                + "&offset=" + offset + "&limit=" + COMMENTS_PER_PAGE);
        return commentPage(content.body(uri, cookie), page, null);
    }

    Comments replies(long workId, boolean novel, long rootId, int rootPage, int page, String cookie) {
        // Pixiv addresses replies by comment ID alone. Verify the root belongs to this visible work.
        if (comments(workId, novel, rootPage, cookie).items().stream().noneMatch(item -> item.id() == rootId))
            throw ContentFailure.notFound();
        String kind = novel ? "novels" : "illusts";
        var uri = URI.create(WEB + "/ajax/" + kind + "/comments/replies?comment_id=" + rootId + "&page=" + page);
        return commentPage(content.body(uri, cookie), page, rootId);
    }

    private Comments commentPage(JsonNode result, int page, Long rootId) {
        if (!result.path("hasNext").isBoolean()) throw ContentFailure.invalidResponse();
        var items = new ArrayList<Comment>();
        for (var row : array(result.path("comments"))) {
            if (rootId != null && id(row.path("commentRootId")) != rootId) throw ContentFailure.invalidResponse();
            boolean deleted = row.path("isDeletedUser").asBoolean(false);
            items.add(new Comment(id(row.path("id")), deleted ? null : id(row.path("userId")),
                    deleted ? null : text(row, "userName"), row.path("comment").asString(null),
                    row.hasNonNull("stampId") ? id(row.path("stampId")) : null,
                    text(row, "commentDate"), row.path("hasReplies").asBoolean(false)));
        }
        return new Comments(page, result.path("hasNext").asBoolean(), items);
    }
}
