package top.sywyar.pixivdownload.core.metadata.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.time.EpochMillisNormalizer;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * 作品 meta 归一化器（捕获 meta 的唯一 curation 规范化器）：把一份捕获到的 Pixiv 原始 body
 * （计划任务后端自抓 / 后续前端转发）归一化成可落盘的 sidecar 文档（schemaVersion=1）+ 列投影值。
 *
 * <p><b>后端是「剪枝 + 白名单」权威</b>：无论来源是否可信，都在此独立剪一遍——剥掉 C 类
 * （计数族 / 会话私有 {@code bookmarkData}/{@code likeData} / 巨型噪声 {@code userIllusts}/{@code zoneConfig}/…），
 * 小说额外剥正文 {@code content}（已在 {@code raw_content}）与内嵌图 {@code textEmbeddedImages}（已在 {@code novel_images}）；
 * 高价值 B 抽成 {@code normalized} typed 块，其余 A+B 原样留在剪枝后的 {@code raw} 块。整体设总上限兜底超大字段。
 *
 * <p>本类不做 IO；落盘与列写入由 {@link WorkSidecarStore} / {@link WorkMetaCaptureService} 承担。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkMetaCurator {

    /** sidecar 顶层 schema 版本。 */
    static final int SCHEMA_VERSION = 1;

    /** sidecar 文档序列化后的总上限（字节）；超限<b>拒绝整份 sidecar</b>，绝不落出 {@code raw} 残缺的半成品。 */
    private static final int MAX_DOCUMENT_BYTES = 256 * 1024;

    /** 高价值 typed 文本字段的单字段长度上限。 */
    private static final int MAX_TEXT_FIELD = 8192;

    /** C 类：两类作品共同剔除（计数族 / 会话私有 / 巨型噪声 / 推广 / 投票）。 */
    private static final Set<String> COMMON_STRIP = Set.of(
            "bookmarkCount", "likeCount", "commentCount", "viewCount", "responseCount",
            "imageResponseCount", "imageResponseData", "imageResponseOutData",
            "bookmarkData", "likeData",
            "userIllusts", "userNovels", "zoneConfig", "extraData",
            "comicPromotion", "fanboxPromotion",
            "contestBanners", "contestData", "pollData",
            "descriptionBoothId", "descriptionYoutubeId");

    /** 小说额外剔除：正文与内嵌图已有专属落点，留在 sidecar 会成倍冗余。 */
    private static final Set<String> NOVEL_EXTRA_STRIP = Set.of("content", "textEmbeddedImages");

    private final ObjectMapper objectMapper;

    /**
     * 归一化插画捕获 meta。
     *
     * @param workId     作品 ID
     * @param illustBody {@code /ajax/illust/{id}} 的 body（非空）
     * @param pagesBody  {@code /ajax/illust/{id}/pages} 的 body（逐页尺寸 / 原图 URL）；动图等无逐页时传 {@code null}
     * @param source     捕获来源（{@code schedule}/{@code forward}/{@code backfill}）
     */
    public CuratedWorkMeta curateArtwork(long workId, JsonNode illustBody, JsonNode pagesBody, String source) {
        Long uploadTime = parseEpochMillis(illustBody, "uploadDate", "createDate");
        Boolean isOriginal = boolOrNull(illustBody, "isOriginal");

        ObjectNode normalized = objectMapper.createObjectNode();
        putEpoch(normalized, "uploadTime", uploadTime);
        putEpoch(normalized, "createTime", parseEpochMillis(illustBody, "createDate"));
        putEpoch(normalized, "reuploadTime", parseEpochMillis(illustBody, "reuploadDate"));
        putBool(normalized, "isOriginal", isOriginal);
        putBool(normalized, "isUnlisted", boolOrNull(illustBody, "isUnlisted"));
        putTitleCaptionTranslation(normalized, illustBody);
        putIllustCommentIfDiffers(normalized, illustBody);
        putPages(normalized, pagesBody);

        ObjectNode raw = prune(illustBody, COMMON_STRIP);
        return assemble(WorkType.ARTWORK, workId, source, normalized, raw, uploadTime, isOriginal);
    }

    /**
     * 归一化小说捕获 meta。{@code novelBody} = {@code /ajax/novel/{id}} 的 body（非空）。
     */
    public CuratedWorkMeta curateNovel(long workId, JsonNode novelBody, String source) {
        Long uploadTime = parseEpochMillis(novelBody, "uploadDate", "createDate", "updateDate");
        if (uploadTime == null) {
            // 小说兼容 uploadTimestamp（前端转发的 body 可能只带它）：epoch 毫秒/秒数字或 ISO 字符串，类型安全。
            uploadTime = parseFlexibleEpochMillis(novelBody.path("uploadTimestamp"));
        }
        Boolean isOriginal = boolOrNull(novelBody, "isOriginal");

        ObjectNode normalized = objectMapper.createObjectNode();
        putEpoch(normalized, "uploadTime", uploadTime);
        putEpoch(normalized, "createTime", parseEpochMillis(novelBody, "createDate"));
        putEpoch(normalized, "reuploadTime", parseEpochMillis(novelBody, "reuploadDate"));
        putBool(normalized, "isOriginal", isOriginal);
        putBool(normalized, "isUnlisted", boolOrNull(novelBody, "isUnlisted"));
        putTitleCaptionTranslation(normalized, novelBody);

        ObjectNode raw = prune(novelBody, COMMON_STRIP);
        NOVEL_EXTRA_STRIP.forEach(raw::remove);
        return assemble(WorkType.NOVEL, workId, source, normalized, raw, uploadTime, isOriginal);
    }

    private CuratedWorkMeta assemble(WorkType workType, long workId, String source,
                                     ObjectNode normalized, ObjectNode raw,
                                     Long uploadTime, Boolean isOriginal) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("schemaVersion", SCHEMA_VERSION);
        doc.put("workType", workType.name());
        doc.put("workId", workId);
        doc.put("fetchedAt", Instant.now().toString());
        doc.put("source", source);
        doc.set("normalized", normalized);
        doc.set("raw", raw);

        // 总上限兜底：超大（典型为恶意 / 异常巨型 raw）时<b>拒绝整份 sidecar</b>——绝不落出 raw 残缺的半成品，
        // 否则插件会看到「存在但 raw 不可恢复」的假成功 sidecar。列投影（uploadTime/isOriginal）随返回值带出、仍有效。
        if (estimateBytes(doc) > MAX_DOCUMENT_BYTES) {
            log.warn("sidecar document for {} {} exceeds {} bytes; rejecting sidecar (column projection kept)",
                    workType, workId, MAX_DOCUMENT_BYTES);
            return new CuratedWorkMeta(uploadTime, isOriginal, null);
        }
        return new CuratedWorkMeta(uploadTime, isOriginal, doc);
    }

    /** 深拷贝 body 并剔除给定 C 类键；非对象 body 退化为空对象。 */
    private ObjectNode prune(JsonNode body, Set<String> stripKeys) {
        if (body == null || !body.isObject()) {
            return objectMapper.createObjectNode();
        }
        ObjectNode copy = body.deepCopy();
        stripKeys.forEach(copy::remove);
        return copy;
    }

    private void putPages(ObjectNode normalized, JsonNode pagesBody) {
        if (pagesBody == null || !pagesBody.isArray() || pagesBody.isEmpty()) {
            return;
        }
        ArrayNode pages = normalized.putArray("pages");
        for (JsonNode page : pagesBody) {
            ObjectNode p = pages.addObject();
            p.put("width", page.path("width").asInt(0));
            p.put("height", page.path("height").asInt(0));
            String original = page.path("urls").path("original").asText("");
            if (!original.isEmpty()) {
                p.put("original", original);
            }
        }
    }

    private void putTitleCaptionTranslation(ObjectNode normalized, JsonNode body) {
        JsonNode tct = body.path("titleCaptionTranslation");
        if (!tct.isObject()) {
            return;
        }
        String title = textOrNull(tct, "workTitle");
        String caption = textOrNull(tct, "workCaption");
        if (title != null) {
            normalized.put("titleTranslation", truncate(title));
        }
        if (caption != null) {
            normalized.put("captionTranslation", truncate(caption));
        }
    }

    /** 作者评论常等于 description（已是 A 类列），仅当不同才捕获、避免冗余整段正文。 */
    private void putIllustCommentIfDiffers(ObjectNode normalized, JsonNode body) {
        String comment = textOrNull(body, "illustComment");
        if (comment == null) {
            return;
        }
        String description = body.path("description").asText("");
        if (!comment.equals(description)) {
            normalized.put("illustComment", truncate(comment));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static Long parseEpochMillis(JsonNode body, String... fields) {
        for (String field : fields) {
            String iso = textOrNull(body, field);
            if (iso == null) {
                continue;
            }
            try {
                return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
            } catch (Exception ignored) {
                // 下一个候选字段
            }
        }
        return null;
    }

    /**
     * 解析「可能是 epoch 毫秒 / epoch 秒数字，或 ISO 字符串」的时间值为 epoch 毫秒。
     * 数字经 {@link EpochMillisNormalizer} 在毫秒/秒间消歧；非法字符串（既非 ISO 又非数字）返回
     * {@code null}，<b>绝不</b>退化成 0。
     */
    private static Long parseFlexibleEpochMillis(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return normalizeEpochMillis(node.asLong());
        }
        if (node.isTextual()) {
            String text = node.asText("").trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return OffsetDateTime.parse(text).toInstant().toEpochMilli();
            } catch (Exception ignored) {
                // 非 ISO，再试纯数字串
            }
            try {
                return normalizeEpochMillis(Long.parseLong(text));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** epoch 秒 ↔ 毫秒消歧；{@code <= 0} 视为无效。 */
    private static Long normalizeEpochMillis(long value) {
        if (value <= 0) {
            return null;
        }
        return EpochMillisNormalizer.normalize(value);
    }

    private static Boolean boolOrNull(JsonNode body, String field) {
        JsonNode node = body.path(field);
        return node.isBoolean() ? node.asBoolean() : null;
    }

    private static String textOrNull(JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText("");
        return text.isEmpty() ? null : text;
    }

    private static void putEpoch(ObjectNode node, String field, Long value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static void putBool(ObjectNode node, String field, Boolean value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static String truncate(String value) {
        return value.length() <= MAX_TEXT_FIELD ? value : value.substring(0, MAX_TEXT_FIELD);
    }

    private int estimateBytes(JsonNode node) {
        try {
            return objectMapper.writeValueAsBytes(node).length;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }
}
