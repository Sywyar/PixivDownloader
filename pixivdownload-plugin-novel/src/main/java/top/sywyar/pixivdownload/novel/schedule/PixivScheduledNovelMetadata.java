package top.sywyar.pixivdownload.novel.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.core.db.TagDto;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pixiv 小说 AJAX body 的 novel 模块内解析结果。 */
record PixivScheduledNovelMetadata(
        long novelId,
        String title,
        int xRestrict,
        boolean ai,
        int bookmarkCount,
        Long authorId,
        String authorName,
        String description,
        List<TagDto> tags,
        Long seriesId,
        Long seriesOrder,
        String seriesTitle,
        String content,
        Integer wordCount,
        Integer textLength,
        Integer readingTimeSeconds,
        Integer pageCount,
        boolean original,
        String language,
        String coverUrl,
        Long uploadTimestamp,
        Map<String, String> embeddedImages) {

    private static final long EPOCH_MILLIS_THRESHOLD = 1_000_000_000_000L;

    static PixivScheduledNovelMetadata parse(long novelId, JsonNode body) {
        Long seriesId = null;
        Long seriesOrder = null;
        String seriesTitle = null;
        JsonNode nav = body.path("seriesNavData");
        if (nav.isObject()) {
            long parsedSeriesId = nav.path("seriesId").asLong(0L);
            if (parsedSeriesId > 0L) {
                seriesId = parsedSeriesId;
                seriesOrder = nav.path("order").asLong(0L);
                seriesTitle = nav.path("title").asText("");
            }
        }
        String content = body.path("content").asText("");
        return new PixivScheduledNovelMetadata(
                novelId,
                body.path("title").asText(""),
                body.path("xRestrict").asInt(0),
                body.path("aiType").asInt(0) >= 2,
                body.path("bookmarkCount").asInt(-1),
                positiveLong(body.path("userId").asText(null)),
                body.path("userName").asText(""),
                body.path("description").asText(""),
                tags(body),
                seriesId,
                seriesOrder,
                seriesTitle,
                content,
                body.has("wordCount") ? body.path("wordCount").asInt(0) : null,
                body.has("characterCount") ? body.path("characterCount").asInt(0) : null,
                readingTimeSeconds(body),
                pageCount(content),
                body.path("isOriginal").asBoolean(false),
                body.path("language").asText(""),
                novelCoverUrl(body),
                uploadTimestamp(body),
                embeddedImages(body));
    }

    static SeriesMetadata parseSeries(JsonNode body) {
        return new SeriesMetadata(
                body.path("caption").asText(""),
                seriesCoverUrl(body),
                tags(body));
    }

    boolean matches(PixivScheduledNovelDefinition.Filters filters) {
        if (!contentMatches(filters.content(), xRestrict)) {
            return false;
        }
        if ("exclude".equals(filters.aiFilter()) && ai) {
            return false;
        }
        if ("only".equals(filters.aiFilter()) && !ai) {
            return false;
        }
        if (wordCount != null && wordCount > 0) {
            if (filters.wordsMin() != null && wordCount < filters.wordsMin()) {
                return false;
            }
            if (filters.wordsMax() != null && wordCount > filters.wordsMax()) {
                return false;
            }
        }
        if (bookmarkCount >= 0) {
            if (filters.bookmarksMin() != null && bookmarkCount < filters.bookmarksMin()) {
                return false;
            }
            if (filters.bookmarksMax() != null && bookmarkCount > filters.bookmarksMax()) {
                return false;
            }
        }
        List<String> tokens = tagTokens(tags);
        return allTagsMatch(tokens, filters.tagsExact(), false)
                && allTagsMatch(tokens, filters.tagsFuzzy(), true);
    }

    private static boolean contentMatches(String content, int xRestrict) {
        if (content == null) {
            return true;
        }
        return switch (content) {
            case "safe" -> xRestrict == 0;
            case "r18plus" -> xRestrict >= 1;
            case "r18" -> xRestrict == 1;
            case "r18g" -> xRestrict == 2;
            default -> true;
        };
    }

    private static boolean allTagsMatch(List<String> tokens, List<String> required, boolean fuzzy) {
        for (String needle : required) {
            boolean matched = false;
            for (String token : tokens) {
                if (fuzzy ? token.contains(needle) : token.equals(needle)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static List<String> tagTokens(List<TagDto> tags) {
        List<String> tokens = new ArrayList<>();
        for (TagDto tag : tags) {
            if (tag.getName() != null && !tag.getName().isBlank()) {
                tokens.add(tag.getName().toLowerCase(Locale.ROOT));
            }
            if (tag.getTranslatedName() != null && !tag.getTranslatedName().isBlank()) {
                tokens.add(tag.getTranslatedName().toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    private static List<TagDto> tags(JsonNode body) {
        JsonNode values = body.path("tags").path("tags");
        if (!values.isArray() || values.isEmpty()) {
            values = body.path("tags");
        }
        if (!values.isArray() || values.isEmpty()) {
            return List.of();
        }
        List<TagDto> tags = new ArrayList<>();
        for (JsonNode value : values) {
            String name = value.isTextual()
                    ? value.asText("")
                    : value.path("tag").asText(value.path("name").asText(""));
            if (name.isEmpty()) {
                continue;
            }
            String translated = null;
            JsonNode translation = value.path("translation");
            if (translation.isObject()) {
                String english = translation.path("en").asText("");
                if (!english.isEmpty()) {
                    translated = english;
                }
            }
            tags.add(new TagDto(name, translated));
        }
        return List.copyOf(tags);
    }

    private static String novelCoverUrl(JsonNode body) {
        for (String parent : List.of("imageUrls", "urls")) {
            JsonNode urls = body.path(parent);
            if (urls.isObject()) {
                for (String key : List.of("original", "large", "regular", "medium", "squareMedium")) {
                    String value = urls.path(key).asText("");
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            }
        }
        for (String key : List.of("coverUrl", "url", "thumbnailUrl")) {
            String value = body.path(key).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String seriesCoverUrl(JsonNode body) {
        JsonNode urls = body.path("cover").path("urls");
        if (urls.isObject()) {
            for (String key : List.of("original", "1200x1200", "720x720", "480mw", "240mw")) {
                String value = urls.path(key).asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        for (String key : List.of("coverImageUrl", "coverImage", "thumbnailUrl")) {
            String value = body.path(key).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Integer readingTimeSeconds(JsonNode body) {
        for (String field : List.of(
                "readingTimeSeconds", "readingTime", "readTime", "estimatedReadingTime")) {
            JsonNode value = body.path(field);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                int seconds = value.asInt(0);
                return seconds > 0 ? seconds : null;
            }
            String digits = value.asText("").replaceAll("[^0-9]", "");
            if (digits.isEmpty()) {
                continue;
            }
            try {
                int seconds = Integer.parseInt(digits);
                return seconds > 0 ? seconds : null;
            } catch (NumberFormatException ignored) {
                // 尝试下一个兼容字段。
            }
        }
        return null;
    }

    private static int pageCount(String content) {
        if (content == null || content.isEmpty()) {
            return 1;
        }
        int count = 1;
        int index = 0;
        while ((index = content.indexOf("[newpage]", index)) >= 0) {
            count++;
            index += "[newpage]".length();
        }
        return count;
    }

    private static Long uploadTimestamp(JsonNode body) {
        for (String field : List.of("uploadDate", "createDate", "updateDate")) {
            String value = body.path(field).asText(null);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                return OffsetDateTime.parse(value).toInstant().toEpochMilli();
            } catch (RuntimeException ignored) {
                // 继续兼容其它时间字段。
            }
        }
        return flexibleEpochMillis(body.path("uploadTimestamp"));
    }

    private static Long flexibleEpochMillis(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return normalizeEpochMillis(value.asLong());
        }
        if (!value.isTextual()) {
            return null;
        }
        String text = value.asText("").trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            // 非 ISO 值继续按十进制 epoch 解析。
        }
        try {
            return normalizeEpochMillis(Long.parseLong(text));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long normalizeEpochMillis(long value) {
        if (value <= 0L) {
            return null;
        }
        return value >= EPOCH_MILLIS_THRESHOLD ? value : value * 1000L;
    }

    private static Map<String, String> embeddedImages(JsonNode body) {
        JsonNode values = body.path("textEmbeddedImages");
        if (!values.isObject() || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> images = new LinkedHashMap<>();
        values.fields().forEachRemaining(entry -> {
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
        return Map.copyOf(images);
    }

    private static Long positiveLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0L ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    record SeriesMetadata(String description, String coverUrl, List<TagDto> tags) {
    }
}
