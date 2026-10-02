package top.sywyar.pixivdownload.pixivbatchdownloaderimport;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImportRequest;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import java.nio.file.Path;
import java.util.*;

/** 只接受完整成功集合及其同批元数据；浏览器不能选择宿主源目录。 */
final class PixivBatchDownloaderImportObservation {
    private PixivBatchDownloaderImportObservation() {}
    static WorkFileImportRequest parse(JsonNode input, Path root) {
        require(input != null && input.isObject() && input.path("schemaVersion").isInt()
                && input.path("schemaVersion").intValue() == 1
                && input.path("source").asText().equals("pixiv-batch-downloader"), "INVALID_OBSERVATION");
        require(input.path("taskBatch").isIntegralNumber() && input.path("taskBatch").longValue() > 0
                && input.path("tabId").isIntegralNumber(), "UNKNOWN_BATCH");
        JsonNode meta = input.path("metadata");
        int type = meta.path("type").asInt(-1);
        require(meta.path("type").isInt() && type >= 0 && type <= 3, "UNKNOWN_WORK_TYPE");
        long id = positiveId(meta.path("id"));
        WorkType workType = type == 3 ? WorkType.NOVEL : WorkType.ARTWORK;
        require(meta.path("pageCount").isInt(), "INVALID_PAGE_COUNT");
        int count = type >= 2 ? 1 : meta.path("pageCount").intValue();
        require(count > 0 && count <= 1000 && (type < 2 || meta.path("pageCount").intValue() == 1), "INVALID_PAGE_COUNT");
        // Pixiv 的 0 表示未标注；沿用下载链路，仅 2 记为已标记 AI 生成。
        require(meta.path("aiType").isInt() && meta.path("aiType").intValue() >= 0
                && meta.path("aiType").intValue() <= 2, "UNKNOWN_AI_TYPE");
        require(meta.path("restriction").isInt(), "UNKNOWN_RESTRICTION");
        JsonNode observations = input.path("files");
        require(observations.isArray() && observations.size() == count, "INCOMPLETE_PAGE_SET");
        Path[] paths = new Path[count];
        for (JsonNode file : observations) {
            require(file.path("outcome").asText().equals("success") && !file.path("noReply").asBoolean(), "UNCONFIRMED_FILE");
            require(file.path("page").isInt(), "INVALID_PAGE");
            int page = file.path("page").intValue();
            require(page >= 0 && page < count && paths[page] == null, "AMBIGUOUS_PAGE");
            require(file.path("fileId").asText().equals(type >= 2 ? Long.toString(id) : id + "_p" + page), "FILE_ID_MISMATCH");
            String name = text(file.path("path"));
            require(name.length() <= 4096 && !name.contains("\0"), "INVALID_SOURCE_PATH");
            Path path = root.resolve(name).toAbsolutePath().normalize();
            require(root.isAbsolute() && path.startsWith(root.normalize()), "INVALID_SOURCE_PATH");
            paths[page] = path;
        }
        require(meta.path("tags").isArray() && meta.path("tags").size() <= 256, "UNKNOWN_TAGS");
        List<WorkTag> tags = new ArrayList<>();
        for (JsonNode tag : meta.path("tags")) tags.add(new WorkTag(null, text(tag), null));
        String description = optional(meta.path("description"));
        require(description == null || description.length() <= 32768, "INVALID_DESCRIPTION");
        description = top.sywyar.pixivdownload.core.pixiv.PixivDescriptionHtml.normalizeLinks(description);
        return new WorkFileImportRequest(workType, id, text(meta.path("title")), count,
                meta.path("restriction").intValue(), meta.path("aiType").intValue() == 2,
                positiveId(meta.path("authorId")), optional(meta.path("authorName")), description,
                meta.path("seriesId").isNull() || meta.path("seriesId").isMissingNode() ? null : positiveId(meta.path("seriesId")),
                meta.path("seriesOrder").isIntegralNumber() ? meta.path("seriesOrder").longValue() : null,
                tags, root, Arrays.asList(paths), type == 3 ? optional(meta.path("novelContent")) : null);
    }
    private static long positiveId(JsonNode node) {
        String value = node.asText(); require(value.matches("[1-9][0-9]{0,15}"), "INVALID_ID"); return Long.parseLong(value);
    }
    private static String text(JsonNode node) {
        require(node.isTextual() && !node.textValue().isBlank(), "MISSING_TEXT"); return node.textValue();
    }
    private static String optional(JsonNode node) { return node.isTextual() ? node.textValue() : null; }
    private static void require(boolean condition, String code) { if (!condition) throw new InvalidObservation(code); }
    static final class InvalidObservation extends IllegalArgumentException {
        InvalidObservation(String code) { super(code); }
    }
}
