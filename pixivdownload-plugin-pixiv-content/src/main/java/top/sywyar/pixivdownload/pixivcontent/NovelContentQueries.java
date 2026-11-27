package top.sywyar.pixivdownload.pixivcontent;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.pixivcontent.NovelContentModels.*;

import static top.sywyar.pixivdownload.pixivcontent.ContentQueries.*;

/** Read-only novel queries; text is returned exactly as supplied by Pixiv. */
final class NovelContentQueries {
    private static final int SERIES_PAGE_SIZE = 30;
    private static final int USER_PAGE_SIZE = 24;
    private static final DateTimeFormatter RANKING_DATE = DateTimeFormatter.ofPattern("M/d/uuuu", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private final ContentQueries content;

    NovelContentQueries(ContentQueries content) {
        this.content = content;
    }

    NovelList search(String word, String order, String mode, String sMode, LocalDate startDate, LocalDate endDate,
                     int page, String cookie) {
        return search(word, order, mode, sMode, startDate, endDate, page, cookie, SearchFilters.NONE);
    }

    NovelList search(String word, String order, String mode, String sMode, LocalDate startDate, LocalDate endDate,
                     int page, String cookie, SearchFilters filters) {
        var endpoint = UriComponentsBuilder.fromUriString(WEB + "/ajax/search/novels");
        filters.apply(endpoint);
        var results = content.body(searchUri(endpoint, word, order, mode, sMode, startDate, endDate, page), cookie).path("novel");
        List<NovelCard> items = new ArrayList<>();
        for (JsonNode entry : array(results.path("data"))) {
            if (!entry.path("isAdContainer").asBoolean(false) && !entry.path("isMasked").asBoolean(false)) {
                items.add(card(entry));
            }
        }
        return new NovelList(page, nonnegative(results.path("total")),
                page < nonnegative(results.path("lastPage")), items);
    }

    Novel novel(long novelId, String cookie) {
        JsonNode info = novelInfo(novelId, cookie);
        String raw = text(info, "content");
        return new Novel(novelId, text(info, "title"), id(info.path("userId")), text(info, "userName"),
                text(info, "language"), detailTags(info), seriesMembership(info), "pixiv-novel-text", raw,
                coverPath(novelId, info.path("coverUrl")), NovelImages.resolve(content, novelId, raw, info, cookie),
                info.path("description").asString(null), optionalInstant(info.path("createDate")),
                optionalInstant(info.path("uploadDate")), optionalCount(info.path("characterCount")),
                optionalCount(info.path("bookmarkCount")), optionalCount(info.path("viewCount")),
                optionalCount(info.path("likeCount")), optionalCount(info.path("commentCount")),
                optionalLevel(info.path("xRestrict")), optionalLevel(info.path("aiType")));
    }

    private JsonNode novelInfo(long novelId, String cookie) {
        JsonNode info = content.body(URI.create(WEB + "/ajax/novel/" + novelId), cookie);
        if (id(info.path("id")) != novelId) throw ContentFailure.invalidResponse();
        if (info.path("isMasked").asBoolean(false)) throw ContentFailure.upstream(403);
        return info;
    }

    URI cover(long novelId, String cookie) {
        var url = novelInfo(novelId, cookie).path("coverUrl");
        if (url.asString("").isBlank()) throw ContentFailure.notFound();
        return cdnUri(url.asString());
    }

    URI uploadedImage(long novelId, long imageId, String cookie) {
        return NovelImages.uploaded(novelInfo(novelId, cookie), imageId);
    }

    NovelList userNovels(long userId, int page, String cookie, String... tags) {
        if (tags != null && tags.length > 0) {
            var matches = AuthorTagSearch.search(content, userId, "novels", tags, page, USER_PAGE_SIZE, cookie);
            return new NovelList(page, matches.total(), matches.hasNext(),
                    matches.works().stream().map(NovelContentQueries::card).toList());
        }
        String endpoint = WEB + "/ajax/user/" + userId;
        var index = content.body(URI.create(endpoint + "/profile/all"), cookie).path("novels");
        if (index.isArray() && index.isEmpty()) return new NovelList(page, 0, false, List.of());
        if (!index.isObject()) throw ContentFailure.invalidResponse();
        var ids = new TreeSet<Long>(Comparator.reverseOrder());
        for (String key : index.propertyNames()) ids.add(positiveId(key));
        long offset = (page - 1L) * USER_PAGE_SIZE;
        if (offset >= ids.size()) return new NovelList(page, ids.size(), false, List.of());
        var selected = ids.stream().skip(offset).limit(USER_PAGE_SIZE).toList();
        var request = UriComponentsBuilder.fromUriString(endpoint + "/novels");
        for (long id : selected) request.queryParam("ids[]", id);
        var works = content.body(request.build().encode().toUri(), cookie);
        if (!works.isObject() && !(works.isArray() && works.isEmpty())) throw ContentFailure.invalidResponse();
        List<NovelCard> items = new ArrayList<>();
        for (long id : selected) {
            var entry = works.path(Long.toString(id));
            if (entry.isNull() || entry.isMissingNode() || entry.path("isMasked").asBoolean(false)) continue;
            NovelCard item = card(entry);
            if (item.id() != id || item.userId() != userId) throw ContentFailure.invalidResponse();
            items.add(item);
        }
        return new NovelList(page, ids.size(), offset + USER_PAGE_SIZE < ids.size(), items);
    }

    NovelRanking rankings(String mode, LocalDate date, int page, String cookie) {
        var uri = UriComponentsBuilder.fromUriString(WEB + "/ajax/ranking/novel")
                .queryParam("mode", mode).queryParam("p", page).queryParam("lang", "en");
        if (date != null) uri.queryParam("date", date.format(DateTimeFormatter.BASIC_ISO_DATE));
        var result = content.body(uri.build().toUri(), cookie);
        var display = result.path("display_a");
        if (!mode.equals(display.path("mode").asString("")) || positive(display.path("page")) != page)
            throw ContentFailure.invalidResponse();
        LocalDate published = rankingDate(result, result.hasNonNull("date") ? "date" : "end");
        LocalDate start = result.hasNonNull("start") ? rankingDate(result, "start") : null;
        if ((date != null && !date.equals(published)) || (start != null && start.isAfter(published)))
            throw ContentFailure.invalidResponse();
        var next = display.path("next");
        boolean hasNext = !next.isNull() && positive(next) > page;
        List<RankedNovel> items = new ArrayList<>();
        var ranks = display.path("rank_a");
        // Pixiv serializes a page with removed entries as an object with sparse numeric keys.
        if (!ranks.isArray() && !ranks.isObject()) throw ContentFailure.invalidResponse();
        for (var entry : ranks) {
            if (entry.hasNonNull("mask_reason") && !entry.path("mask_reason").asString("masked").isBlank()) continue;
            var id = id(entry.path("id"));
            var card = new NovelCard(id, text(entry, "title"), id(entry.path("user_id")), text(entry, "user_name"),
                    optionalCount(entry.path("character_count")), novelPath(id), coverPath(id, entry.path("url")),
                    tags(entry.path("tag_a")), entry.path("comment").asString(null), null, null,
                    optionalCount(entry.path("bookmark_count")), optionalLevel(entry.path("x_restrict")),
                    optionalLevel(entry.path("ai_type")));
            items.add(new RankedNovel(positive(entry.path("rank")), card));
        }
        if (ranks.isObject()) items.sort(Comparator.comparingInt(RankedNovel::rank));
        return new NovelRanking(mode, published, start, page, hasNext, items);
    }

    private static LocalDate rankingDate(JsonNode result, String field) {
        try {
            return LocalDate.parse(text(result, field), RANKING_DATE);
        } catch (DateTimeParseException invalid) {
            throw ContentFailure.invalidResponse();
        }
    }

    NovelSeries series(long seriesId, int page, String cookie) {
        JsonNode meta = content.body(URI.create(WEB + "/ajax/novel/series/" + seriesId), cookie);
        if (id(meta.path("id")) != seriesId) throw ContentFailure.invalidResponse();
        long authorId = id(meta.path("userId"));
        String author = text(meta, "userName");
        int total = nonnegative(meta.path("publishedContentCount"));
        long offset = (page - 1L) * SERIES_PAGE_SIZE;
        List<NovelChapter> chapters = new ArrayList<>();
        if (offset < total) {
            URI uri = UriComponentsBuilder.fromUriString(WEB + "/ajax/novel/series_content/" + seriesId)
                    .queryParam("limit", SERIES_PAGE_SIZE).queryParam("last_order", offset)
                    .queryParam("order_by", "asc").build().toUri();
            JsonNode results = content.body(uri, cookie).path("page").path("seriesContents");
            for (JsonNode entry : array(results)) {
                JsonNode membership = entry.path("series");
                if (id(membership.path("id")) != seriesId) throw ContentFailure.invalidResponse();
                if (entry.path("isMasked").asBoolean(false)) continue;
                long chapterAuthor = id(entry.path("userId"));
                if (chapterAuthor != authorId) throw ContentFailure.invalidResponse();
                chapters.add(new NovelChapter(positive(membership.path("contentOrder")), card(entry, authorId, author)));
            }
        }
        return new NovelSeries(seriesId, text(meta, "title"), authorId, author, text(meta, "caption"), page,
                total, offset + SERIES_PAGE_SIZE < total, chapters);
    }

    static NovelCard card(JsonNode item) {
        return card(item, id(item.path("userId")), text(item, "userName"));
    }

    private static NovelCard card(JsonNode item, long userId, String userName) {
        long novelId = id(item.path("id"));
        JsonNode count = item.hasNonNull("characterCount") ? item.path("characterCount") : item.path("textCount");
        var cover = item.hasNonNull("url") ? item.path("url") : item.path("coverUrl");
        return new NovelCard(novelId, text(item, "title"), userId, userName, optionalCount(count), novelPath(novelId),
                coverPath(novelId, cover), tags(item.path("tags")), item.path("description").asString(null),
                optionalInstant(item.path("createDate")), optionalInstant(item.path("updateDate")),
                optionalCount(item.path("bookmarkCount")), optionalLevel(item.path("xRestrict")), optionalLevel(item.path("aiType")));
    }

    static List<String> tags(JsonNode values) {
        if (values.isMissingNode() || values.isNull()) return List.of();
        List<String> tags = new ArrayList<>();
        for (var value : array(values)) {
            if (!value.isString()) throw ContentFailure.invalidResponse();
            tags.add(value.asString());
        }
        return tags;
    }

    static String novelPath(long id) {
        return PixivContentPlugin.API + "/novels/" + id;
    }

    private static String coverPath(long id, JsonNode url) {
        return url.asString("").isBlank() ? null : novelPath(id) + "/cover";
    }
}
