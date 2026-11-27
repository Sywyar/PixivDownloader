package top.sywyar.pixivdownload.pixivcontent;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.function.LongPredicate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.core.web.HttpParameterException;

import static top.sywyar.pixivdownload.pixivcontent.ContentModels.*;
import static top.sywyar.pixivdownload.pixivcontent.ContentQueries.*;
import static top.sywyar.pixivdownload.pixivcontent.PersonalContentModels.*;

final class BrowseQueries {
    private static final String AJAX = WEB + "/ajax/";
    private static final int TAGS_PER_PAGE = 10;
    private final ContentQueries content;

    BrowseQueries(ContentQueries content) {
        this.content = content;
    }

    FollowingPage users(String word, int page, boolean exact, String cookie) {
        var uri = UriComponentsBuilder.fromUriString(AJAX + "search/users").queryParam("nick", "{word}")
                .queryParam("s_mode", exact ? "s_usr_full" : "s_usr").queryParam("i", 0).queryParam("p", page)
                .encode().buildAndExpand(word).toUri();
        var body = content.browseBody(uri, cookie);
        var info = body.path("page");
        int total = nonnegative(info.path("total"));
        var users = new LinkedHashMap<Long, User>();
        var ids = array(info.path("userIds"));
        var profiles = body.path("users");
        if (!profiles.isObject() && !profiles.isArray() && !ids.isEmpty()) throw ContentFailure.invalidResponse();
        for (var row : profiles) {
            long uid = id(row.hasNonNull("userId") ? row.path("userId") : row.path("id"));
            String avatar = row.path("imageBig").asString(row.path("image").asString(""));
            users.put(uid, new User(uid, text(row, row.hasNonNull("name") ? "name" : "userName"), row.path("comment").asString(""),
                    avatar.isBlank() ? null : cdnUri(avatar).toString()));
        }
        var items = new ArrayList<User>();
        for (var key : ids) {
            var user = users.get(id(key));
            if (user == null) throw ContentFailure.invalidResponse();
            items.add(user);
        }
        return new FollowingPage(page, total, page * 10L < total, items);
    }

    TagPage tags(String word, int page, TagSuggestions suggested) {
        var uri = searchUri(UriComponentsBuilder.fromUriString(AJAX + "search/top"), word,
                "date_d", "safe", "s_tag", null, null, 1);
        var body = content.body(uri, null);
        var unique = new LinkedHashMap<String, TagSuggestion>();
        suggested.items().forEach(tag -> unique.putIfAbsent(tag.name(), tag));
        for (var node : array(body.path("relatedTags"))) {
            String name = node.asString("");
            if (name.isBlank()) throw ContentFailure.invalidResponse();
            String translation = body.path("tagTranslation").path(name).path("en").asString(null);
            var existing = unique.get(name);
            if (existing == null || existing.translation() == null && translation != null)
                unique.put(name, new TagSuggestion(name, translation));
        }
        long offset = (page - 1L) * TAGS_PER_PAGE;
        return new TagPage(page, unique.size(), offset + TAGS_PER_PAGE < unique.size(),
                unique.values().stream().skip(offset).limit(TAGS_PER_PAGE).toList());
    }

    CollectionPage collections(String word, int page, String cookie) {
        var uri = searchUri(UriComponentsBuilder.fromUriString(AJAX + "search/collections"), word,
                "date_d", "all", "s_tag", null, null, page);
        var results = content.body(uri, cookie).path("collection");
        var cards = array(results.path("data")).valueStream().filter(PersonalContentQueries::isPublicCollection)
                .map(row -> PersonalContentQueries.collectionCard(row, true)).toList();
        return new CollectionPage(page, nonnegative(results.path("total")),
                page < nonnegative(results.path("lastPage")), cards);
    }

    BookmarkTags bookmarkTags(String userId, boolean novel, boolean privateTags, String cookie) {
        var result = content.body(URI.create(AJAX + "user/" + userId + (novel ? "/novels" : "/illusts")
                + "/bookmark/tags"), cookie);
        var tags = array(result.path(privateTags ? "private" : "public")).valueStream()
                .map(row -> new BookmarkTag(text(row, "tag"), nonnegative(row.path("cnt").isMissingNode()
                        ? row.path("total") : row.path("cnt")))).toList();
        return new BookmarkTags(tags);
    }

    Watchlist watchlist(SeriesType type, int page, String cookie) {
        var result = content.body(URI.create(AJAX + "watch_list/" + type + "?p=" + page
                + (type == SeriesType.novel ? "&new=1" : "")), cookie).path("page");
        String base = PixivContentPlugin.API + (type == SeriesType.novel ? "/novel-series/" : "/series/");
        var items = array(result.path("watchedSeriesIds")).valueStream().map(ContentQueries::id)
                .map(id -> new SeriesLink(id, base + id)).toList();
        return new Watchlist(type, page, nonnegative(result.path("total")), page < nonnegative(result.path("maxPage")), items);
    }

    WorkFeed discover(boolean novel, String cookie, LongPredicate visible) {
        String kind = novel ? "novel" : "illust";
        var result = content.body(URI.create(AJAX + kind + "/discovery?mode=all&max=18"), cookie);
        return feed(array(result.path(kind + "s")), novel, null, visible, 18);
    }

    WorkFeed homepage(boolean novel, String cookie, LongPredicate visible) {
        String kind = novel ? "novel" : "illust";
        var result = content.body(URI.create(AJAX + "top/" + kind + "?mode=all"), cookie);
        var works = PersonalContentQueries.thumbnails(result.path("thumbnails").path(kind));
        var items = new ArrayList<PersonalCard>();
        for (var key : array(result.path("page").path("recommend").path("ids"))) {
            long id = id(key);
            if (works.containsKey(id) && visible.test(id)) items.add(PersonalContentQueries.card(works.get(id), novel));
        }
        return new WorkFeed(items, null);
    }

    WorkFeed latest(boolean novel, boolean manga, Long cursor, String cookie, LongPredicate visible) {
        if (cursor != null && cursor <= 0) throw new HttpParameterException("query", "cursor", "out_of_range");
        String kind = novel ? "novel" : "illust";
        var result = content.browseBody(URI.create(AJAX + kind + "/new?lastId=" + (cursor == null ? 0 : cursor)
                + "&limit=20&r18=false" + (novel ? "" : "&type=" + (manga ? "manga" : "illust"))), cookie);
        var rows = array(result.path(kind + "s"));
        var next = result.path("lastId");
        if (next.isMissingNode()) throw ContentFailure.invalidResponse();
        Long nextId = next.isNull() || next.asString("").equals("0") ? null : id(next);
        if (cursor != null && nextId != null && nextId >= cursor) throw ContentFailure.invalidResponse();
        return feed(rows, novel, nextId, visible, 20);
    }

    private static WorkFeed feed(JsonNode rows, boolean novel, Long cursor, LongPredicate visible, int limit) {
        var items = rows.valueStream().filter(PersonalContentQueries::available)
                .filter(row -> visible.test(id(row.path("id")))).limit(limit).map(row -> PersonalContentQueries.card(row, novel)).toList();
        return new WorkFeed(items, cursor);
    }

    GroupedNovels groupedNovels(String word, String order, String mode, String sMode, LocalDate start, LocalDate end,
                                int page, String cookie, SearchFilters filters, LongPredicate visible) {
        var uri = UriComponentsBuilder.fromUriString(AJAX + "search/novels").queryParam("gs", 1);
        filters.apply(uri);
        var result = content.body(searchUri(uri, word, order, mode, sMode, start, end, page), cookie).path("novel");
        var items = new ArrayList<GroupedNovel>();
        for (var row : array(result.path("data"))) {
            if (!PersonalContentQueries.available(row)) continue;
            if (!row.path("isOneshot").isBoolean()) throw ContentFailure.invalidResponse();
            boolean single = row.path("isOneshot").asBoolean();
            long id = id(row.path(single ? "novelId" : "id"));
            if (single && !visible.test(id)) continue;
            items.add(new GroupedNovel(id, single ? "novel" : "series", text(row, "title"), id(row.path("userId")),
                    text(row, "userName"), PixivContentPlugin.API + (single ? "/novels/" : "/novel-series/") + id));
        }
        return new GroupedNovels(page, nonnegative(result.path("total")), page < nonnegative(result.path("lastPage")), items);
    }
}
