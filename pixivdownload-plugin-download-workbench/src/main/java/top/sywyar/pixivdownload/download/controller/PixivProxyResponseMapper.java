package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import top.sywyar.pixivdownload.core.pixiv.PixivCoverUrlResolver;
import top.sywyar.pixivdownload.core.pixiv.PixivDescriptionHtml;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.download.response.artwork.ArtworkMetaResponse;
import top.sywyar.pixivdownload.download.response.artwork.ArtworkPagesResponse;
import top.sywyar.pixivdownload.download.response.artwork.SeriesResponse;
import top.sywyar.pixivdownload.download.response.artwork.UgoiraMetaResponse;
import top.sywyar.pixivdownload.download.response.collection.CollectionPageResponse;
import top.sywyar.pixivdownload.download.response.collection.CollectionWorksResponse;
import top.sywyar.pixivdownload.download.response.search.SearchResponse;
import top.sywyar.pixivdownload.download.response.user.FollowingPageResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 Pixiv AJAX 响应归一化为下载工作台的稳定响应类型。
 */
final class PixivProxyResponseMapper {

    private static final Set<String> FORWARDED_META_STRIP_KEYS = Set.of(
            "userIllusts", "userNovels", "zoneConfig", "extraData", "noLoginData",
            "comicPromotion", "fanboxPromotion", "contestBanners", "contestData",
            "pollData", "imageResponseData", "imageResponseOutData");

    private PixivProxyResponseMapper() {
    }

    static ArtworkMetaResponse artworkMeta(ObjectMapper objectMapper, JsonNode body) throws IOException {
        JsonNode nav = body.path("seriesNavData");
        Long seriesId = null;
        Long seriesOrder = null;
        String seriesTitle = null;
        if (nav.isObject()) {
            long sid = nav.path("seriesId").asLong(0);
            if (sid > 0) {
                seriesId = sid;
                seriesOrder = nav.path("order").asLong(0);
                seriesTitle = nav.path("title").asText("");
            }
        }
        return new ArtworkMetaResponse(
                body.path("illustType").asInt(0),
                body.path("illustTitle").asText(""),
                body.path("xRestrict").asInt(0),
                body.path("aiType").asInt(0) >= 2,
                body.path("bookmarkCount").asInt(-1),
                body.path("pageCount").asInt(0),
                parsePositiveLong(body.path("userId").asText(null)),
                body.path("userName").asText(""),
                PixivDescriptionHtml.normalizeLinks(body.path("description").asText("")),
                extractTags(body),
                seriesId,
                seriesOrder,
                seriesTitle,
                forwardedMetaJson(objectMapper, body));
    }

    private static String forwardedMetaJson(ObjectMapper objectMapper, JsonNode body) throws IOException {
        if (!body.isObject()) {
            return null;
        }
        ObjectNode pruned = body.deepCopy();
        FORWARDED_META_STRIP_KEYS.forEach(pruned::remove);
        return objectMapper.writeValueAsString(pruned);
    }

    static ArtworkPagesResponse artworkPages(JsonNode body) {
        List<String> urls = new ArrayList<>();
        for (JsonNode page : body) {
            String originalUrl = page.path("urls").path("original").asText("");
            if (!originalUrl.isEmpty()) {
                urls.add(originalUrl);
            }
        }
        return new ArtworkPagesResponse(urls);
    }

    static UgoiraMetaResponse ugoiraMeta(JsonNode body) {
        String zipUrl = body.path("originalSrc").asText("");
        if (zipUrl.isEmpty()) {
            zipUrl = body.path("src").asText("");
        }
        List<Integer> delays = new ArrayList<>();
        for (JsonNode frame : body.path("frames")) {
            delays.add(frame.path("delay").asInt(100));
        }
        return new UgoiraMetaResponse(zipUrl, delays);
    }

    static SearchResponse searchResponse(JsonNode illustManga, int page) {
        List<SearchResponse.SearchItem> items = new ArrayList<>();
        for (JsonNode item : illustManga.path("data")) {
            items.add(searchItem(item));
        }
        return new SearchResponse(items, illustManga.path("total").asInt(0), page);
    }

    /**
     * 把用户插画字典按请求 ID 顺序转为卡片，跳过缺失或已删除作品。
     */
    static List<SearchResponse.SearchItem> parseUserIllustCards(JsonNode body, List<String> ids) {
        List<SearchResponse.SearchItem> items = new ArrayList<>();
        if (body == null || ids == null) {
            return items;
        }
        for (String id : ids) {
            JsonNode item = body.path(id);
            if (item.isMissingNode() || item.isNull() || !item.isObject()) {
                continue;
            }
            items.add(searchItem(item, id));
        }
        return items;
    }

    static SeriesResponse seriesResponse(JsonNode body, long requestedSeriesId, int page) {
        JsonNode seriesArray = body.path("illustSeries");
        long seriesId = requestedSeriesId;
        String title = "";
        Long authorId = null;
        String authorName = "";
        int total = 0;
        String caption = "";
        String coverUrl = "";
        if (seriesArray.isArray() && !seriesArray.isEmpty()) {
            JsonNode series = seriesArray.get(0);
            seriesId = parsePositiveOrDefault(series.path("id").asText(null), requestedSeriesId);
            title = series.path("title").asText("");
            authorId = parsePositiveLong(series.path("userId").asText(null));
            total = series.path("total").asInt(0);
            caption = series.path("caption").asText("");
            coverUrl = extractSeriesCoverUrl(series);
        }
        JsonNode users = body.path("users");
        if (authorId != null && users.isArray()) {
            for (JsonNode user : users) {
                if (user.path("userId").asText("").equals(String.valueOf(authorId))) {
                    authorName = user.path("name").asText("");
                    break;
                }
            }
        }

        Map<String, Integer> orderById = new LinkedHashMap<>();
        JsonNode seriesPage = body.path("page").path("series");
        if (seriesPage.isArray()) {
            for (JsonNode entry : seriesPage) {
                String id = entry.path("workId").asText("");
                if (!id.isEmpty()) {
                    orderById.put(id, entry.path("order").asInt(0));
                }
            }
        }

        List<SeriesResponse.SeriesItem> items = new ArrayList<>();
        JsonNode thumbnails = body.path("thumbnails").path("illust");
        if (thumbnails.isArray()) {
            int fallbackOrder = (page - 1) * 12;
            for (JsonNode item : thumbnails) {
                String id = item.path("id").asText("");
                if (id.isEmpty()) {
                    continue;
                }
                int seriesOrder = orderById.getOrDefault(id, ++fallbackOrder);
                items.add(new SeriesResponse.SeriesItem(
                        id,
                        item.path("title").asText(""),
                        item.path("illustType").asInt(0),
                        item.path("xRestrict").asInt(0),
                        item.path("aiType").asInt(0),
                        item.path("url").asText(""),
                        item.path("pageCount").asInt(1),
                        item.path("userId").asText(""),
                        item.path("userName").asText(""),
                        seriesOrder,
                        parseStringTags(item.path("tags"))));
            }
            List<SeriesResponse.SeriesItem> filtered = new ArrayList<>();
            for (SeriesResponse.SeriesItem item : items) {
                if (orderById.containsKey(item.id())) {
                    filtered.add(item);
                }
            }
            if (!filtered.isEmpty()) {
                filtered.sort(Comparator.comparingInt(SeriesResponse.SeriesItem::seriesOrder));
                items = filtered;
            }
        }
        boolean lastPage = items.size() < 12 || (total > 0 && page * 12 >= total);
        return new SeriesResponse(
                new SeriesResponse.SeriesMeta(
                        seriesId, title, authorId, authorName, total, caption, coverUrl),
                items,
                page,
                lastPage);
    }

    static SearchResponse bookmarkResponse(JsonNode body, int offset, int limit) {
        List<SearchResponse.SearchItem> items = new ArrayList<>();
        for (JsonNode item : body.path("works")) {
            items.add(searchItem(item));
        }
        return new SearchResponse(items, body.path("total").asInt(0), offset / limit + 1);
    }

    static FollowingPageResponse followingPageResponse(JsonNode body, int offset, int limit) {
        List<FollowingPageResponse.FollowingUser> users = new ArrayList<>();
        for (JsonNode user : body.path("users")) {
            users.add(new FollowingPageResponse.FollowingUser(
                    user.path("userId").asText(""),
                    user.path("userName").asText(""),
                    user.path("profileImageUrl").asText(""),
                    user.path("userComment").asText(user.path("comment").asText(""))));
        }
        return new FollowingPageResponse(users, body.path("total").asInt(0), offset, limit);
    }

    /** 按 Pixiv 返回的 ID 顺序组装关注新作卡片。 */
    static List<SearchResponse.SearchItem> parseFollowLatestIllusts(JsonNode body) {
        List<SearchResponse.SearchItem> items = new ArrayList<>();
        if (body == null) {
            return items;
        }
        Map<String, JsonNode> illustById = new LinkedHashMap<>();
        for (JsonNode item : body.path("thumbnails").path("illust")) {
            String id = item.path("id").asText("");
            if (!id.isBlank()) {
                illustById.put(id, item);
            }
        }
        JsonNode ids = body.path("page").path("ids");
        if (ids.isArray() && !ids.isEmpty()) {
            for (JsonNode id : ids) {
                JsonNode item = illustById.get(id.asText(""));
                if (item != null) {
                    items.add(searchItem(item));
                }
            }
        } else {
            for (JsonNode item : illustById.values()) {
                items.add(searchItem(item));
            }
        }
        return items;
    }

    static boolean followLatestHasNext(JsonNode body, int pageItemCount) {
        if (body != null) {
            JsonNode lastPage = body.path("page").path("isLastPage");
            if (lastPage.isBoolean()) {
                return !lastPage.asBoolean();
            }
        }
        return pageItemCount > 0;
    }

    static List<String> collectionIds(JsonNode body) {
        List<String> ids = new ArrayList<>();
        JsonNode idArray = body.path("collectionIds");
        if (idArray.isArray()) {
            for (JsonNode id : idArray) {
                String value = id.asText("");
                if (!value.isBlank()) {
                    ids.add(value);
                }
            }
        }
        if (ids.isEmpty()) {
            JsonNode collections = body.path("collections");
            if (collections.isObject()) {
                collections.fieldNames().forEachRemaining(ids::add);
            }
        }
        return ids;
    }

    static List<CollectionPageResponse.CollectionItem> collectionItems(JsonNode works) {
        List<CollectionPageResponse.CollectionItem> collections = new ArrayList<>();
        if (!works.isArray()) {
            return collections;
        }
        for (JsonNode collection : works) {
            collections.add(new CollectionPageResponse.CollectionItem(
                    collection.path("id").asText(""),
                    collection.path("title").asText(""),
                    collection.path("caption").asText(""),
                    collection.path("thumbnailImageUrl").asText(""),
                    collection.path("bookmarkCount").asInt(0),
                    collection.path("xRestrict").asInt(0),
                    parseStringTags(collection.path("tags"))));
        }
        return collections;
    }

    /** 按珍藏集 tile 顺序组装插画与小说混合作品列表。 */
    static List<CollectionWorksResponse.Work> parseCollectionWorks(JsonNode body) {
        List<CollectionWorksResponse.Work> works = new ArrayList<>();
        if (body == null) {
            return works;
        }
        Map<String, JsonNode> illustById = new LinkedHashMap<>();
        for (JsonNode item : body.path("thumbnails").path("illust")) {
            illustById.put(item.path("id").asText(""), item);
        }
        Map<String, JsonNode> novelById = new LinkedHashMap<>();
        for (JsonNode item : body.path("thumbnails").path("novel")) {
            novelById.put(item.path("id").asText(""), item);
        }
        JsonNode tiles = body.path("data").path("detail").path("tiles");
        if (!tiles.isArray()) {
            return works;
        }
        for (JsonNode tile : tiles) {
            if (!"Work".equals(tile.path("type").asText(""))
                    || !"Active".equals(tile.path("status").asText("Active"))) {
                continue;
            }
            String workType = tile.path("workType").asText("");
            String workId = tile.path("workId").asText("");
            if (workId.isBlank()) {
                continue;
            }
            if ("novel".equals(workType)) {
                JsonNode item = novelById.get(workId);
                if (item != null) {
                    works.add(novelWork(workId, item));
                }
            } else {
                JsonNode item = illustById.get(workId);
                if (item != null) {
                    works.add(illustWork(workId, item));
                }
            }
        }
        return works;
    }

    private static CollectionWorksResponse.Work novelWork(String workId, JsonNode item) {
        return new CollectionWorksResponse.Work(
                "novel",
                workId,
                item.path("title").asText(""),
                0,
                item.path("xRestrict").asInt(0),
                item.path("aiType").asInt(0),
                extractNovelCoverUrl(item),
                1,
                item.path("userId").asText(""),
                item.path("userName").asText(""),
                parseStringTags(item.path("tags")),
                item.path("bookmarkCount").asInt(-1),
                item.path("wordCount").asInt(0),
                item.path("textLength").asInt(item.path("characterCount").asInt(0)),
                item.path("isOriginal").asBoolean(false));
    }

    private static CollectionWorksResponse.Work illustWork(String workId, JsonNode item) {
        return new CollectionWorksResponse.Work(
                "illust",
                workId,
                item.path("title").asText(""),
                item.path("illustType").asInt(0),
                item.path("xRestrict").asInt(0),
                item.path("aiType").asInt(0),
                item.path("url").asText(""),
                item.path("pageCount").asInt(1),
                item.path("userId").asText(""),
                item.path("userName").asText(""),
                parseStringTags(item.path("tags")),
                item.path("bookmarkCount").asInt(-1),
                0,
                0,
                false);
    }

    private static SearchResponse.SearchItem searchItem(JsonNode item) {
        return searchItem(item, "");
    }

    private static SearchResponse.SearchItem searchItem(JsonNode item, String fallbackId) {
        return new SearchResponse.SearchItem(
                item.path("id").asText(fallbackId),
                item.path("title").asText(""),
                item.path("illustType").asInt(0),
                item.path("xRestrict").asInt(0),
                item.path("aiType").asInt(0),
                item.path("url").asText(""),
                item.path("pageCount").asInt(1),
                item.path("userId").asText(""),
                item.path("userName").asText(""),
                parseStringTags(item.path("tags")));
    }

    private static List<String> parseStringTags(JsonNode tagsNode) {
        if (tagsNode == null || !tagsNode.isArray() || tagsNode.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (JsonNode tag : tagsNode) {
            String value = tag.isTextual() ? tag.asText("") : tag.path("tag").asText("");
            value = value.trim();
            if (!value.isEmpty()) {
                tags.add(value);
            }
        }
        return new ArrayList<>(tags);
    }

    private static Long parsePositiveLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long parsePositiveOrDefault(String value, long fallback) {
        Long parsed = parsePositiveLong(value);
        return parsed == null ? fallback : parsed;
    }

    private static List<WorkTag> extractTags(JsonNode body) {
        JsonNode tags = body.path("tags").path("tags");
        if (!tags.isArray() || tags.isEmpty()) {
            tags = body.path("tags");
        }
        if (!tags.isArray() || tags.isEmpty()) {
            return List.of();
        }
        List<WorkTag> values = new ArrayList<>();
        for (JsonNode tag : tags) {
            String name = tag.isTextual()
                    ? tag.asText("")
                    : tag.path("tag").asText(tag.path("name").asText(""));
            if (name.isEmpty()) {
                continue;
            }
            String translated = null;
            JsonNode translation = tag.path("translation");
            if (translation.isObject()) {
                String english = translation.path("en").asText("");
                if (!english.isEmpty()) {
                    translated = english;
                }
            }
            values.add(new WorkTag(null, name, translated));
        }
        return values;
    }

    private static String extractSeriesCoverUrl(JsonNode meta) {
        JsonNode urls = meta.path("cover").path("urls");
        if (urls.isObject()) {
            for (String key : List.of("original", "1200x1200", "720x720", "480mw", "240mw")) {
                String value = urls.path(key).asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        for (String key : List.of("coverImageUrl", "coverImage", "thumbnailUrl")) {
            String value = meta.path(key).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String extractNovelCoverUrl(JsonNode node) {
        for (String parent : List.of("imageUrls", "urls")) {
            JsonNode urls = node.path(parent);
            if (urls.isObject()) {
                for (String key : List.of("original", "large", "regular", "medium", "squareMedium")) {
                    String cover = urls.path(key).asText("");
                    if (!cover.isBlank()) {
                        return PixivCoverUrlResolver.preferHighResolution(cover);
                    }
                }
            }
        }
        for (String key : List.of("coverUrl", "url", "thumbnailUrl")) {
            String cover = node.path(key).asText("");
            if (!cover.isBlank()) {
                return PixivCoverUrlResolver.preferHighResolution(cover);
            }
        }
        return "";
    }
}
