package top.sywyar.pixivdownload.novel.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import static top.sywyar.pixivdownload.novel.schedule.PixivScheduleDefaults.*;

/** 小说执行器对当前 Pixiv 计划任务定义的模块内只读投影。 */
record PixivScheduledNovelDefinition(
        JsonNode source,
        Filters filters,
        Download download) {

    static final String SCHEMA = "pixiv.schedule.definition";
    static final int VERSION = 1;

    static PixivScheduledNovelDefinition parse(ObjectMapper objectMapper, ScheduledTaskDefinition definition) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(definition, "definition");
        if (!SCHEMA.equals(definition.definitionSchema()) || definition.definitionVersion() != VERSION) {
            throw new IllegalArgumentException("unsupported Pixiv schedule definition");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(definition.definitionJson());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid Pixiv schedule definition JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Pixiv schedule definition must be a JSON object");
        }
        String kind = root.path("kind").asText(KIND);
        boolean novelDefinition = "novel".equalsIgnoreCase(kind);
        boolean mixedCollectionDefinition = "mixed".equalsIgnoreCase(kind)
                && "collection".equals(definition.sourceType());
        if (!novelDefinition && !mixedCollectionDefinition) {
            throw new IllegalArgumentException("Pixiv schedule definition is not for novels");
        }
        return new PixivScheduledNovelDefinition(
                root.path("source"),
                parseFilters(root.path("filters")),
                parseDownload(root.path("download")));
    }

    long seriesId() {
        Long value = longOrNull(source.path("seriesId"));
        return value == null ? 0L : value;
    }

    private static Filters parseFilters(JsonNode filters) {
        return new Filters(
                filters.path("content").asText(FILTERS_CONTENT),
                filters.path("aiFilter").asText(FILTERS_AI_FILTER),
                loweredList(filters.path("tagsExact")),
                loweredList(filters.path("tagsFuzzy")),
                intOrNull(filters.path("wordsMin")),
                intOrNull(filters.path("wordsMax")),
                intOrNull(filters.path("bookmarksMin")),
                intOrNull(filters.path("bookmarksMax")));
    }

    private static Download parseDownload(JsonNode download) {
        String template = download.path("fileNameTemplate").asText(DOWNLOAD_FILE_NAME_TEMPLATE);
        return new Download(
                template.isBlank() ? null : template,
                download.path("bookmark").asBoolean(DOWNLOAD_BOOKMARK),
                longOrNull(download.path("collectionId")),
                download.path("redownloadDeleted").asBoolean(DOWNLOAD_REDOWNLOAD_DELETED),
                download.path("novelFormat").asText(DOWNLOAD_NOVEL_FORMAT),
                download.path("novelMerge").asBoolean(DOWNLOAD_NOVEL_MERGE),
                download.path("novelMergeFormat").asText(DOWNLOAD_NOVEL_MERGE_FORMAT),
                download.path("novelAutoTranslate").asBoolean(DOWNLOAD_NOVEL_AUTO_TRANSLATE),
                download.path("novelTranslateLanguage").asText(DOWNLOAD_NOVEL_TRANSLATE_LANGUAGE),
                intOrNull(download.path("novelTranslateSegmentSize")),
                top.sywyar.pixivdownload.core.work.service.DownloadPathAction.parse(
                        download.path("pathOverflowAction").asText(DOWNLOAD_PATH_OVERFLOW_ACTION)));
    }

    private static List<String> loweredList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static Integer intOrNull(JsonNode node) {
        if (node.isNumber()) {
            return node.asInt();
        }
        if (node.isTextual() && !node.asText().isBlank()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException ignored) {
                // 旧定义中的非法可选值继续按未设置处理。
            }
        }
        return null;
    }

    private static Long longOrNull(JsonNode node) {
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isTextual() && !node.asText().isBlank()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException ignored) {
                // 旧定义中的非法可选值继续按未设置处理。
            }
        }
        return null;
    }

    record Filters(
            String content,
            String aiFilter,
            List<String> tagsExact,
            List<String> tagsFuzzy,
            Integer wordsMin,
            Integer wordsMax,
            Integer bookmarksMin,
            Integer bookmarksMax) {
    }

    record Download(
            String fileNameTemplate,
            boolean bookmark,
            Long collectionId,
            boolean redownloadDeleted,
            String novelFormat,
            boolean novelMerge,
            String novelMergeFormat,
            boolean novelAutoTranslate,
            String novelTranslateLanguage,
            Integer novelTranslateSegmentSize,
            top.sywyar.pixivdownload.core.work.service.DownloadPathAction pathOverflowAction) {
    }
}
