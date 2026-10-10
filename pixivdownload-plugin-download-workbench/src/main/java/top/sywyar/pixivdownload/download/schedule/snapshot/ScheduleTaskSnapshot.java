package top.sywyar.pixivdownload.download.schedule.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.work.service.DownloadPathAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import static top.sywyar.pixivdownload.download.schedule.snapshot.PixivScheduleDefaults.*;

/**
 * 计划任务 {@code params_json} 的模块内解析结果。
 *
 * <p>该类型只负责把持久化 JSON 归一为调度执行所需的来源、筛选与下载设置，不参与来源发现、
 * 队列编排或下载派发。解析保持旧快照的默认值语义，供 {@code ScheduleExecutor} 在一次运行内复用。
 */
public record ScheduleTaskSnapshot(
        boolean novel,
        JsonNode source,
        Filters filters,
        Download download,
        int fetchLimit,
        boolean cookieDependent) {

    private static final String KIND_NOVEL = "novel";

    /** 从持久化的任务参数 JSON 解析一次运行所需的快照。 */
    public static ScheduleTaskSnapshot parse(ObjectMapper objectMapper, String paramsJson)
            throws JsonProcessingException {
        Objects.requireNonNull(objectMapper, "objectMapper");
        return from(objectMapper.readTree(paramsJson == null ? "{}" : paramsJson));
    }

    /** 从已解析的根节点构造快照，便于纯函数测试复用。 */
    public static ScheduleTaskSnapshot from(JsonNode root) {
        Objects.requireNonNull(root, "root");
        return new ScheduleTaskSnapshot(
                KIND_NOVEL.equalsIgnoreCase(root.path("kind").asText(KIND)),
                root.path("source"),
                parseFilters(root.path("filters")),
                parseDownload(root.path("download")),
                Math.max(0, root.path("fetchLimit").asInt(FETCH_LIMIT)),
                isCookieDependent(root));
    }

    /** 解析任务快照中的服务端筛选条件。 */
    public static Filters parseFilters(JsonNode filters) {
        return new Filters(
                filters.path("content").asText(FILTERS_CONTENT),
                filters.path("aiFilter").asText(FILTERS_AI_FILTER),
                readLoweredList(filters.path("tagsExact")),
                readLoweredList(filters.path("tagsFuzzy")),
                filters.path("typeFilter").asText(FILTERS_TYPE_FILTER),
                intOrNull(filters.path("pagesMin")), intOrNull(filters.path("pagesMax")),
                intOrNull(filters.path("wordsMin")), intOrNull(filters.path("wordsMax")),
                intOrNull(filters.path("bookmarksMin")), intOrNull(filters.path("bookmarksMax")));
    }

    /** 解析任务快照中的下载设置。 */
    public static Download parseDownload(JsonNode download) {
        var media = top.sywyar.pixivdownload.download.media.MediaOutputSettings.fromJson(download);
        String template = download.path("fileNameTemplate").asText(DOWNLOAD_FILE_NAME_TEMPLATE);
        return new Download(
                template.isBlank() ? null : template,
                download.path("bookmark").asBoolean(DOWNLOAD_BOOKMARK),
                longOrNull(download.path("collectionId")),
                Math.max(1, download.path("concurrent").asInt(DOWNLOAD_CONCURRENT)),
                longOrNull(download.path("intervalMs")),
                intOrNull(download.path("imageDelayMs")),
                download.path("verifyFiles").asBoolean(DOWNLOAD_VERIFY_FILES),
                download.path("redownloadDeleted").asBoolean(DOWNLOAD_REDOWNLOAD_DELETED),
                download.path("novelFormat").asText(DOWNLOAD_NOVEL_FORMAT),
                download.path("novelMerge").asBoolean(DOWNLOAD_NOVEL_MERGE),
                download.path("novelMergeFormat").asText(DOWNLOAD_NOVEL_MERGE_FORMAT),
                download.path("novelAutoTranslate").asBoolean(DOWNLOAD_NOVEL_AUTO_TRANSLATE),
                download.path("novelTranslateLanguage").asText(DOWNLOAD_NOVEL_TRANSLATE_LANGUAGE),
                intOrNull(download.path("novelTranslateSegmentSize")),
                DownloadPathAction.parse(download.path("pathOverflowAction").asText(DOWNLOAD_PATH_OVERFLOW_ACTION)),
                media.getImageFormats(), media.getUgoiraFormats(),
                media.getQuality(), media.isWebpLossless(), media.getMaximumEdge());
    }

    /**
     * 判定 dead bound cookie 是否会让发现结果缩水或让下载后动作失效。
     * 缺失的 {@code filters.content} 仍按旧逻辑视为 {@code safe}，不能改用筛选解析的 {@code all} 默认值。
     */
    private static boolean isCookieDependent(JsonNode root) {
        String content = root.path("filters").path("content").asText(CREDENTIAL_CONTENT);
        if (!"safe".equals(content)) {
            return true;
        }
        if ("r18".equals(root.path("source").path("mode").asText(""))) {
            return true;
        }
        return root.path("download").path("bookmark").asBoolean(DOWNLOAD_BOOKMARK);
    }

    private static List<String> readLoweredList(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode item : array) {
                String value = item.asText("").trim().toLowerCase(Locale.ROOT);
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private static Integer intOrNull(JsonNode node) {
        if (node.isNumber()) {
            return node.asInt();
        }
        if (node.isTextual() && !node.asText().isBlank()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException ignored) {
                // 非法旧值保持 null，由既有默认行为兜底。
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
                // 非法旧值保持 null，由既有默认行为兜底。
            }
        }
        return null;
    }

    /** 任务快照的筛选条件（来自 {@code params_json.filters}）。 */
    public record Filters(String content, String aiFilter, List<String> tagsExact, List<String> tagsFuzzy,
                          String typeFilter, Integer pagesMin, Integer pagesMax,
                          Integer wordsMin, Integer wordsMax, Integer bookmarksMin, Integer bookmarksMax) {
    }

    /**
     * 任务快照的下载设置（来自 {@code params_json.download}）。
     *
     * @param concurrent         最大并发数（作品级），实际值还会按下载池大小收窄
     * @param intervalMs         作品间隔（毫秒），{@code null} / 0 不延迟
     * @param imageDelayMs       图片间隔（毫秒，仅插画），{@code null} / 0 不延迟
     * @param verifyFiles        插画去重时是否校验实际文件
     * @param redownloadDeleted 是否把软删除记录视为可重新下载
     */
    public record Download(String fileNameTemplate, boolean bookmark, Long collectionId,
                           int concurrent, Long intervalMs, Integer imageDelayMs, boolean verifyFiles,
                           boolean redownloadDeleted,
                           String novelFormat, boolean novelMerge, String novelMergeFormat,
                           boolean novelAutoTranslate, String novelTranslateLanguage,
                           Integer novelTranslateSegmentSize, DownloadPathAction pathOverflowAction,
                           String imageFormats, String ugoiraFormats,
                           int mediaQuality, boolean mediaWebpLossless, int mediaMaximumEdge) {
    }
}
