package top.sywyar.pixivdownload.novel.controller;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.core.pixiv.PixivCoverUrlResolver;
import top.sywyar.pixivdownload.core.pixiv.PixivDescriptionHtml;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.novel.response.NovelMetaResponse;
import top.sywyar.pixivdownload.novel.response.NovelSearchResponse;
import top.sywyar.pixivdownload.novel.response.NovelSeriesResponse;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Slf4j
final class NovelPixivResponseMapper {

    private NovelPixivResponseMapper() {
    }

    static NovelMetaResponse novelMeta(long novelId, JsonNode body, String fetchToken) {
        Long seriesId = null;
        Long seriesOrder = null;
        String seriesTitle = null;
        JsonNode nav = body.path("seriesNavData");
        if (nav.isObject()) {
            long parsedSeriesId = nav.path("seriesId").asLong(0);
            if (parsedSeriesId > 0) {
                seriesId = parsedSeriesId;
                seriesOrder = nav.path("order").asLong(0);
                seriesTitle = nav.path("title").asText("");
            }
        }
        String content = body.path("content").asText("");
        return new NovelMetaResponse(
                novelId,
                body.path("title").asText(""),
                body.path("xRestrict").asInt(0),
                body.path("aiType").asInt(0) >= 2,
                body.path("bookmarkCount").asInt(-1),
                parsePositiveLong(body.path("userId").asText(null)),
                body.path("userName").asText(""),
                PixivDescriptionHtml.normalizeLinks(body.path("description").asText("")),
                extractTags(body),
                seriesId,
                seriesOrder,
                seriesTitle,
                content,
                body.has("wordCount") ? body.path("wordCount").asInt(0) : null,
                body.has("characterCount") ? body.path("characterCount").asInt(0) : null,
                extractReadingTimeSeconds(body),
                countPages(content),
                body.path("isOriginal").asBoolean(false),
                body.path("language").asText(""),
                extractNovelCoverUrl(body),
                extractUploadTimestamp(body),
                extractTextEmbeddedImages(body),
                fetchToken
        );
    }

    static NovelSeriesResponse.NovelSeriesMeta seriesMeta(JsonNode body, long fallbackId) {
        return new NovelSeriesResponse.NovelSeriesMeta(
                parsePositiveOrDefault(body.path("id").asText(null), fallbackId),
                body.path("title").asText(""),
                parsePositiveLong(body.path("userId").asText(null)),
                body.path("userName").asText(""),
                body.path("publishedContentCount").asInt(body.path("total").asInt(0)),
                body.path("language").asText(""),
                body.path("isOriginal").asBoolean(false),
                body.path("publishedTotalCharacterCount").asInt(0),
                body.path("publishedTotalWordCount").asInt(0),
                body.path("caption").asText(""),
                extractSeriesCoverUrl(body),
                extractTags(body)
        );
    }

    static List<NovelSeriesResponse.NovelSeriesItem> seriesItems(
            JsonNode contentRoot,
            Long authorId,
            String authorName
    ) {
        JsonNode itemsNode = contentRoot.path("body").path("page").path("seriesContents");
        if (!itemsNode.isArray() || itemsNode.isEmpty()) {
            itemsNode = contentRoot.path("body").path("seriesContents");
        }
        if (!itemsNode.isArray()) {
            return List.of();
        }
        List<NovelSeriesResponse.NovelSeriesItem> items = new ArrayList<>();
        for (JsonNode item : itemsNode) {
            items.add(new NovelSeriesResponse.NovelSeriesItem(
                    item.path("id").asText(""),
                    item.path("title").asText(""),
                    item.path("xRestrict").asInt(0),
                    item.path("aiType").asInt(0),
                    item.path("wordCount").asInt(0),
                    item.path("textLength").asInt(item.path("characterCount").asInt(0)),
                    extractReadingTimeSeconds(item),
                    item.path("userId").asText(String.valueOf(authorId == null ? "" : authorId)),
                    item.path("userName").asText(authorName),
                    item.path("seriesOrder").asInt(item.path("order").asInt(0)),
                    extractNovelCoverUrl(item),
                    extractUploadTimestamp(item),
                    extractTags(item)
            ));
        }
        return items;
    }

    static NovelSearchResponse search(JsonNode novel, int page) {
        List<NovelSearchResponse.NovelSearchItem> items = new ArrayList<>();
        for (JsonNode item : novel.path("data")) {
            items.add(searchItem(item, item.path("url").asText("")));
        }
        return new NovelSearchResponse(items, novel.path("total").asInt(0), page);
    }

    static NovelSearchResponse bookmarks(JsonNode body, int page) {
        List<NovelSearchResponse.NovelSearchItem> items = new ArrayList<>();
        for (JsonNode item : body.path("works")) {
            items.add(searchItem(item, extractNovelCoverUrl(item)));
        }
        return new NovelSearchResponse(items, body.path("total").asInt(0), page);
    }

    static List<NovelSearchResponse.NovelSearchItem> userNovelCards(JsonNode body, List<String> ids) {
        List<NovelSearchResponse.NovelSearchItem> items = new ArrayList<>();
        if (body == null || ids == null) {
            return items;
        }
        for (String id : ids) {
            JsonNode item = body.path(id);
            if (item.isMissingNode() || item.isNull() || !item.isObject()) {
                continue;
            }
            items.add(new NovelSearchResponse.NovelSearchItem(
                    item.path("id").asText(id),
                    item.path("title").asText(""),
                    item.path("xRestrict").asInt(0),
                    item.path("aiType").asInt(0),
                    item.path("bookmarkCount").asInt(-1),
                    item.path("wordCount").asInt(0),
                    item.path("textLength").asInt(item.path("characterCount").asInt(0)),
                    item.path("userId").asText(""),
                    item.path("userName").asText(""),
                    extractNovelCoverUrl(item),
                    item.path("isOriginal").asBoolean(false),
                    parseStringTags(item.path("tags"))
            ));
        }
        return items;
    }

    private static NovelSearchResponse.NovelSearchItem searchItem(JsonNode item, String coverUrl) {
        return new NovelSearchResponse.NovelSearchItem(
                item.path("id").asText(""),
                item.path("title").asText(""),
                item.path("xRestrict").asInt(0),
                item.path("aiType").asInt(0),
                item.path("bookmarkCount").asInt(-1),
                item.path("wordCount").asInt(0),
                item.path("textLength").asInt(item.path("characterCount").asInt(0)),
                item.path("userId").asText(""),
                item.path("userName").asText(""),
                coverUrl,
                item.path("isOriginal").asBoolean(false),
                parseStringTags(item.path("tags"))
        );
    }

    private static Long parsePositiveLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parsePositiveOrDefault(String value, long fallback) {
        Long parsed = parsePositiveLong(value);
        return parsed == null ? fallback : parsed;
    }

    private static List<WorkTag> extractTags(JsonNode body) {
        JsonNode tagsNode = body.path("tags").path("tags");
        if (!tagsNode.isArray() || tagsNode.isEmpty()) {
            tagsNode = body.path("tags");
        }
        if (!tagsNode.isArray() || tagsNode.isEmpty()) {
            return List.of();
        }
        List<WorkTag> tags = new ArrayList<>();
        for (JsonNode tag : tagsNode) {
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
            tags.add(new WorkTag(null, name, translated));
        }
        return tags;
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

    private static Integer extractReadingTimeSeconds(JsonNode node) {
        for (String fieldName : List.of(
                "readingTimeSeconds", "readingTime", "readTime", "estimatedReadingTime")) {
            JsonNode value = node.path(fieldName);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                int seconds = value.asInt(0);
                return seconds > 0 ? seconds : null;
            }
            String digits = value.asText("").trim().replaceAll("[^0-9]", "");
            if (digits.isEmpty()) {
                continue;
            }
            try {
                int seconds = Integer.parseInt(digits);
                return seconds > 0 ? seconds : null;
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Long extractUploadTimestamp(JsonNode node) {
        for (String fieldName : List.of("uploadDate", "createDate", "updateDate")) {
            Long parsed = parsePixivIsoToEpochMillis(node.path(fieldName).asText(null));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static int countPages(String content) {
        if (content == null || content.isEmpty()) {
            return 1;
        }
        int pages = 1;
        int index = 0;
        while ((index = content.indexOf("[newpage]", index)) >= 0) {
            pages++;
            index += "[newpage]".length();
        }
        return pages;
    }

    private static Long parsePixivIsoToEpochMillis(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            log.debug("Failed to parse Pixiv ISO date: {}", iso, e);
            return null;
        }
    }

    /** 只保留 Pixiv 图片域名下的小说内嵌原图。 */
    private static Map<String, String> extractTextEmbeddedImages(JsonNode body) {
        JsonNode node = body.path("textEmbeddedImages");
        if (!node.isObject() || node.isEmpty()) {
            return Map.of();
        }
        Map<String, String> images = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            String url = entry.getValue().path("urls").path("original").asText("");
            if (url.isBlank()) {
                return;
            }
            try {
                String host = URI.create(url).getHost();
                if (host == null || !host.endsWith(".pximg.net")) {
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                return;
            }
            images.put(entry.getKey(), url);
        });
        return images;
    }
}
