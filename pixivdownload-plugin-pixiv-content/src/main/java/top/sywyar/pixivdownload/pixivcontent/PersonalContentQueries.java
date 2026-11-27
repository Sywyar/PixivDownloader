package top.sywyar.pixivdownload.pixivcontent;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.core.pixiv.PixivCookieUserResolver;
import top.sywyar.pixivdownload.core.web.HttpParameterException;
import top.sywyar.pixivdownload.pixivcontent.PersonalContentModels.CollectionCard;
import top.sywyar.pixivdownload.pixivcontent.PersonalContentModels.CollectionPage;
import top.sywyar.pixivdownload.pixivcontent.PersonalContentModels.FollowingPage;
import top.sywyar.pixivdownload.pixivcontent.PersonalContentModels.PersonalCard;
import top.sywyar.pixivdownload.pixivcontent.PersonalContentModels.PersonalPage;
import top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentials;

/** Personal upstream reads use the same authenticated session throughout one request. */
final class PersonalContentQueries {
    private static final String AJAX = "https://www.pixiv.net/ajax";
    private static final URI SELF = URI.create(AJAX + "/user/self");
    private static final int FOLLOWING_PAGE_SIZE = 24;
    private static final int COLLECTION_PAGE_SIZE = 48;
    private static final Pattern COLLECTION_ID = Pattern.compile("[1-9][0-9]*");
    private static final Pattern SESSION = Pattern.compile("PHPSESSID=[1-9][0-9]*_[^;\\s]+");
    private final ContentQueries content;

    PersonalContentQueries(ContentQueries content) {
        this.content = content;
    }

    PersonalPage bookmarks(String type, String rest, String tag, int page, String cookie) {
        return authenticated(cookie, userId -> bookmarksFor(userId, type, rest, tag, page, cookie));
    }

    PersonalPage publicBookmarks(long userId, String type, String tag, int page, String cookie) {
        return bookmarksFor(Long.toString(userId), type, "show", tag, page, cookie);
    }

    private PersonalPage bookmarksFor(String userId, String type, String rest, String tag, int page, String cookie) {
        boolean novel = "novel".equals(type);
        int limit = novel ? 24 : 48;
        long offset = (page - 1L) * limit;
        String path = AJAX + "/user/" + userId + (novel ? "/novels" : "/illusts") + "/bookmarks";
        URI uri = UriComponentsBuilder.fromUriString(path).queryParam("rest", rest)
                .queryParam("tag", "{tag}").queryParam("offset", offset).queryParam("limit", limit)
                .encode().buildAndExpand(tag == null ? "" : tag).toUri();
        JsonNode body = content.browseBody(uri, cookie);
        int total = ContentQueries.nonnegative(body.path("total"));
        List<PersonalCard> cards = new ArrayList<>();
        for (JsonNode item : ContentQueries.array(body.path("works"))) {
            // Pixiv retains removed works in bookmarks with incomplete metadata and isBookmarkable=false.
            if (available(item) && item.path("isBookmarkable").asBoolean(true)) cards.add(card(item, novel));
        }
        return new PersonalPage(type, page, total, offset + limit < total, cards);
    }

    PersonalPage followLatest(String type, int page, String cookie) {
        return authenticated(cookie, userId -> {
            boolean novel = "novel".equals(type);
            String category = novel ? "novel" : "illust";
            URI uri = URI.create(AJAX + "/follow_latest/" + category + "?mode=all&p=" + page);
            JsonNode body = content.body(uri, cookie);
            var thumbnails = thumbnails(ContentQueries.array(body.path("thumbnails").path(category)));
            JsonNode feed = body.path("page");
            JsonNode ids = feed.path("ids");
            List<PersonalCard> cards = new ArrayList<>();
            if (ids.isMissingNode()) {
                for (JsonNode item : thumbnails.values()) cards.add(card(item, novel));
            } else {
                for (JsonNode key : ContentQueries.array(ids)) {
                    JsonNode item = thumbnails.get(ContentQueries.id(key));
                    if (item != null) cards.add(card(item, novel));
                }
            }
            JsonNode lastPage = feed.path("isLastPage");
            if (!lastPage.isBoolean() && !lastPage.isMissingNode()) throw ContentFailure.invalidResponse();
            boolean pageHasEntries = ids.isMissingNode() ? !thumbnails.isEmpty() : !ids.isEmpty();
            boolean hasNext = lastPage.isBoolean() ? !lastPage.asBoolean() : pageHasEntries;
            return new PersonalPage(type, page, null, hasNext, cards);
        });
    }

    FollowingPage following(String rest, int page, String cookie) {
        return authenticated(cookie, userId -> {
            long offset = (page - 1L) * FOLLOWING_PAGE_SIZE;
            URI uri = UriComponentsBuilder.fromUriString(AJAX + "/user/" + userId + "/following")
                    .queryParam("offset", offset).queryParam("limit", FOLLOWING_PAGE_SIZE)
                    .queryParam("rest", rest).queryParam("tag", "").queryParam("acceptingRequests", 0).build().toUri();
            JsonNode body = content.body(uri, cookie);
            int total = ContentQueries.nonnegative(body.path("total"));
            List<ContentModels.User> users = new ArrayList<>();
            for (JsonNode user : ContentQueries.array(body.path("users"))) {
                users.add(new ContentModels.User(ContentQueries.id(user.path("userId")), ContentQueries.text(user, "userName"),
                        user.path("userComment").asString(user.path("comment").asString("")),
                        optionalImage(user, "profileImageUrl")));
            }
            return new FollowingPage(page, total, offset + FOLLOWING_PAGE_SIZE < total, users);
        });
    }

    CollectionPage collections(int page, String cookie) {
        return authenticated(cookie, userId -> collectionsFor(userId, page, cookie, false));
    }

    CollectionPage collectionsFor(String userId, int page, String cookie, boolean publicOnly) {
        var owned = collectionIds(userId, publicOnly ? null : cookie);
        long offset = (page - 1L) * COLLECTION_PAGE_SIZE;
        if (offset >= owned.size()) return new CollectionPage(page, owned.size(), false, List.of());
        List<String> selection = owned.stream().skip(offset).limit(COLLECTION_PAGE_SIZE).toList();
        var request = UriComponentsBuilder.fromUriString(AJAX + "/user/" + userId + "/profile/collections");
        for (String id : selection) request.queryParam("ids[]", id);
        JsonNode body = content.body(request.build().encode().toUri(), cookie);
        var found = new LinkedHashMap<String, CollectionCard>();
        for (JsonNode item : ContentQueries.array(body.path("works"))) {
            String id = item.path("id").asString("");
            if (!selection.contains(id)) throw ContentFailure.invalidResponse();
            if (publicOnly && !isPublicCollection(item)) continue;
            if (publicOnly && !userId.equals(item.path("userId").asString(""))) throw ContentFailure.invalidResponse();
            found.put(id, collectionCard(item, publicOnly));
        }
        List<CollectionCard> cards = selection.stream().filter(found::containsKey).map(found::get).toList();
        return new CollectionPage(page, owned.size(), offset + COLLECTION_PAGE_SIZE < owned.size(), cards);
    }

    PersonalPage collectionWorks(String collectionId, int page, String cookie) {
        if (collectionId == null || !COLLECTION_ID.matcher(collectionId).matches()) {
            throw new HttpParameterException("path", "collectionId", "invalid_value");
        }
        return authenticated(cookie, userId -> {
            if (!collectionIds(userId, cookie).contains(collectionId)) throw ContentFailure.notFound();
            return readCollection(collectionId, page, cookie, false);
        });
    }

    PersonalPage readCollection(String collectionId, int page, String cookie, boolean publicOnly) {
        if (collectionId == null || !COLLECTION_ID.matcher(collectionId).matches())
            throw new HttpParameterException("path", "collectionId", "invalid_value");
        JsonNode body = content.body(URI.create(AJAX + "/collection/" + collectionId), cookie);
        if (publicOnly && !isPublicCollection(body.path("data").path("userCollections").path(collectionId)))
            throw ContentFailure.notFound();
        JsonNode groups = body.path("thumbnails");
        if (!groups.isObject()) throw ContentFailure.invalidResponse();
        var artwork = thumbnails(groups.path("illust"));
        var novels = thumbnails(groups.path("novel"));
        long offset = (page - 1L) * COLLECTION_PAGE_SIZE;
        List<PersonalCard> cards = new ArrayList<>();
        int total = 0;
        for (JsonNode tile : ContentQueries.array(body.path("data").path("detail").path("tiles"))) {
            if (!"Work".equals(tile.path("type").asString("")) || !"Active".equals(tile.path("status").asString("Active"))) continue;
            String type = tile.path("workType").asString("");
            boolean novel = "novel".equals(type);
            if (!novel && !"illust".equals(type)) continue;
            JsonNode work = (novel ? novels : artwork).get(ContentQueries.id(tile.path("workId")));
            if (work == null) continue;
            if (total >= offset && cards.size() < COLLECTION_PAGE_SIZE) cards.add(card(work, novel));
            total++;
        }
        return new PersonalPage("mixed", page, total, offset + COLLECTION_PAGE_SIZE < total, cards);
    }

    static boolean isPublicCollection(JsonNode item) {
        return "public".equals(item.path("status").asString("")) && item.path("visibilityScope").asInt(-1) == 0;
    }

    static CollectionCard collectionCard(JsonNode item, boolean publicOnly) {
        String id = item.path("id").asString("");
        if (!COLLECTION_ID.matcher(id).matches()) throw ContentFailure.invalidResponse();
        String cover = item.path("thumbnailImageUrl").asString("");
        String embed = "https://embed.pixiv.net/next/collection/" + id + "/";
        if (!cover.isBlank() && !cover.startsWith(embed)) cover = optionalImage(item, "thumbnailImageUrl");
        return new CollectionCard(id, ContentQueries.text(item, "title"), item.path("caption").asString(""),
                cover.isBlank() ? null : cover, NovelContentQueries.tags(item.path("tags")),
                PixivContentPlugin.API + (publicOnly ? "/collections/" : "/me/collections/") + id + "/works");
    }

    private LinkedHashSet<String> collectionIds(String userId, String cookie) {
        JsonNode profile = content.body(URI.create(AJAX + "/user/" + userId + "/profile/all"), cookie);
        if (!profile.isObject()) throw ContentFailure.invalidResponse();
        var owned = new LinkedHashSet<String>();
        JsonNode ids = profile.path("collectionIds");
        if (!ids.isMissingNode()) {
            for (JsonNode id : ContentQueries.array(ids)) owned.add(id.asString(""));
        }
        JsonNode indexed = profile.path("collections");
        if (owned.isEmpty() && indexed.isObject()) owned.addAll(indexed.propertyNames());
        if (owned.stream().anyMatch(id -> !COLLECTION_ID.matcher(id).matches())) throw ContentFailure.invalidResponse();
        return owned;
    }

    static Map<Long, JsonNode> thumbnails(JsonNode items) {
        var index = new LinkedHashMap<Long, JsonNode>();
        if (!items.isMissingNode()) {
            for (JsonNode item : ContentQueries.array(items)) {
                if (available(item)) index.put(ContentQueries.id(item.path("id")), item);
            }
        }
        return index;
    }

    private static String optionalImage(JsonNode item, String field) {
        String image = item.path(field).asString("");
        return image.isBlank() ? null : ContentQueries.cdnUri(image).toString();
    }

    <T> T search(String cookie, SearchFilters filters, java.util.function.Supplier<T> query) {
        if (filters.minBookmarks() == null && filters.maxBookmarks() == null) return query.get();
        return authenticated(cookie, uid -> {
            var profile = content.body(URI.create(AJAX + "/user/" + uid), cookie);
            if (!uid.equals(profile.path("userId").asString("")) || !profile.path("premium").isBoolean())
                throw ContentFailure.invalidResponse();
            if (!profile.path("premium").asBoolean()) throw ContentFailure.premiumRequired();
            return query.get();
        });
    }

    ContentModels.User me(String cookie) {
        return authenticated(cookie, userId -> content.user(Long.parseLong(userId), cookie));
    }

    <T> T authenticated(String cookie, Function<String, T> query) {
        String expectedId = accountId(cookie);
        var selected = AcquisitionCredentials.current();
        if (selected == null) return checked(cookie, expectedId, query);
        if (!selected.primary().equals(cookie)) throw ContentFailure.credentialRequired();
        if (AcquisitionCredentials.candidates(cookie).size() == 1) return checked(cookie, expectedId, query);
        // Keep the source cancellation signal while pinning all upstream calls to the verified session.
        return new AcquisitionCredentials(List.of(cookie)).withCancellation(() -> {
            selected.checkCancelled();
            return false;
        }).call(() -> checked(cookie, expectedId, query));
    }

    private <T> T checked(String cookie, String expectedId, Function<String, T> query) {
        JsonNode self = content.document(SELF, cookie).path("userData");
        if (self.isNull()) throw ContentFailure.upstream(403);
        long confirmedId = ContentQueries.id(self.path("id"));
        if (!Long.toString(confirmedId).equals(expectedId)) throw ContentFailure.upstream(403);
        return query.apply(Long.toString(confirmedId));
    }

    private static String accountId(String cookie) {
        if (cookie == null || cookie.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw ContentFailure.credentialRequired();
        }
        String id = null;
        for (String part : cookie.split(";")) {
            String field = part.trim();
            if (!field.regionMatches(true, 0, "PHPSESSID=", 0, "PHPSESSID=".length())) continue;
            if (id != null || !SESSION.matcher(field).matches()) throw ContentFailure.credentialRequired();
            id = PixivCookieUserResolver.extractUidFromCookie(field);
        }
        if (id == null) throw ContentFailure.credentialRequired();
        return id;
    }

    static boolean available(JsonNode item) {
        return !item.path("isMasked").asBoolean(false) && !item.path("isAdContainer").asBoolean(false);
    }

    static PersonalCard card(JsonNode item, boolean novel) {
        if (novel) {
            var work = NovelContentQueries.card(item);
            return new PersonalCard(work.id(), work.title(), work.userId(), work.userName(), "novel",
                    null, work.characterCount(), work.detailPath(), null, work.coverPath(), work.description(),
                    work.tags(), work.createdAt(), work.uploadedAt(), work.bookmarkCount(), work.xRestrict(), work.aiType());
        }
        var work = ContentQueries.card(item, false);
        return new PersonalCard(work.id(), work.title(), work.userId(), work.userName(), work.type(),
                work.pageCount(), null, "/api/pixiv-content/artworks/" + work.id(), work.previewPath(), null,
                item.hasNonNull("description") ? ContentQueries.text(item, "description") : null,
                NovelContentQueries.tags(item.path("tags")), work.createdAt(), work.uploadedAt(),
                ContentQueries.optionalCount(item.path("bookmarkCount")), work.xRestrict(), work.aiType());
    }
}
