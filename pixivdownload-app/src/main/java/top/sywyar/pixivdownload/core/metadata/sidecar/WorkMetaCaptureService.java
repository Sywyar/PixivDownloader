package top.sywyar.pixivdownload.core.metadata.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.core.metadata.ArtworkMetadataQuality;
import top.sywyar.pixivdownload.core.metadata.WorkMetadataStore;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataCapture;


/** 捕获经过裁剪的作品快照与查询列；入库失败不反报已经完成的下载失败。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkMetaCaptureService implements WorkMetadataCapture {

    private final WorkMetaCurator curator;
    private final WorkMetadataStore metadataStore;
    private final ObjectMapper objectMapper;

    /**
     * 捕获插画 meta：归一化后原子更新数据库快照与查询列。
     *
     * @param illustBody {@code /ajax/illust/{id}} 的 body；为 {@code null} 时直接跳过（无可捕获）
     * @param pagesBody  {@code /ajax/illust/{id}/pages} 的 body（逐页尺寸）；无逐页时传 {@code null}
     * @param source     捕获来源（{@code schedule}/{@code forward}/{@code backfill}）
     */
    public void captureArtwork(long artworkId, JsonNode illustBody, JsonNode pagesBody, String source) {
        if (illustBody == null || !illustBody.isObject()) {
            return;
        }
        CuratedWorkMeta curated;
        try {
            curated = curator.curateArtwork(artworkId, illustBody, pagesBody, source);
        } catch (RuntimeException e) {
            log.warn("Failed to curate artwork meta {}: {}", artworkId, e.getMessage());
            return;
        }
        persist(WorkType.ARTWORK, artworkId, curated,
                ArtworkMetadataQuality.isMeaningfulTitle(artworkId, illustBody.path("illustTitle").asText(null)));
    }

    private void captureArtworkJson(long artworkId, String artworkJson, String pagesJson, String source) {
        JsonNode artworkBody = parseRawJson(artworkId, "artwork", artworkJson);
        if (artworkBody == null || !artworkBody.isObject()) {
            return;
        }
        JsonNode pagesBody = parseRawJson(artworkId, "artwork pages", pagesJson);
        captureArtwork(artworkId, artworkBody, pagesBody, source);
    }

    /**
     * 捕获前端转发的插画 meta：解析油猴脚本随下载请求转发的、轻剪枝后的 {@code /ajax/illust/{id}} body
     * JSON 串后，走与计划任务同一个归一化器（来源标记 {@code forward}）。逐页尺寸 {@code pages} 不随转发
     * （本地图片可派生，留待历史回填），故 {@code pagesBody} 传 {@code null}。
     *
     * <p>转发内容为不可信输入：空串 / 非 JSON / 非对象一律视为无可捕获，仅记日志后跳过、绝不上抛——
     * 不能让转发 meta 的解析失败反报已成功的下载。后端的「剪枝 + 白名单 + 限长」由 {@link WorkMetaCurator} 兜底。
     *
     * @param rawMetaJson 轻剪枝后的 illust body JSON 串；{@code null} / 空白 / 非法时直接跳过
     */
    public void captureForwardedArtwork(long artworkId, String rawMetaJson) {
        if (!StringUtils.hasText(rawMetaJson)) {
            return;
        }
        JsonNode body;
        try {
            body = objectMapper.readTree(rawMetaJson);
        } catch (Exception e) {
            log.warn("Skip forwarded artwork meta {}: invalid JSON ({})", artworkId, e.getMessage());
            return;
        }
        captureArtwork(artworkId, body, null, "forward");
    }

    /**
     * 捕获小说 meta：归一化后写 {@code upload_time} 列投影（小说 {@code is_original} 列在 insert 时已写）及数据库快照。
     *
     * @param novelBody {@code /ajax/novel/{id}} 的 body；为 {@code null} 时直接跳过
     */
    public void captureNovel(long novelId, JsonNode novelBody, String source) {
        if (novelBody == null || !novelBody.isObject()) {
            return;
        }
        CuratedWorkMeta curated;
        try {
            curated = curator.curateNovel(novelId, novelBody, source);
        } catch (RuntimeException e) {
            log.warn("Failed to curate novel meta {}: {}", novelId, e.getMessage());
            return;
        }
        persist(WorkType.NOVEL, novelId, curated, true);
    }

    private void captureNovelJson(long novelId, String novelJson, String source) {
        JsonNode novelBody = parseRawJson(novelId, "novel", novelJson);
        captureNovel(novelId, novelBody, source);
    }

    @Override
    public void capture(
            WorkType type,
            long workId,
            String workJson,
            String supplementalJson,
            String source
    ) {
        if (type == null) {
            return;
        }
        switch (type) {
            case ARTWORK -> captureArtworkJson(workId, workJson, supplementalJson, source);
            case NOVEL -> captureNovelJson(workId, workJson, source);
        }
    }

    /**
     * 捕获前端转发的小说 meta：解析油猴脚本随下载请求转发的、轻剪枝后的 {@code /ajax/novel/{id}} body
     * JSON 串后，走与计划任务同一个归一化器（来源标记 {@code forward}）。
     *
     * <p>转发内容为不可信输入：空串 / 非 JSON / 非对象一律视为无可捕获，仅记日志后跳过、绝不上抛——
     * 不能让转发 meta 的解析失败反报已成功的下载。前端虽已先剪掉正文 {@code content} 与内嵌图
     * {@code textEmbeddedImages}，后端的「剪枝 + 白名单 + 限长」仍由 {@link WorkMetaCurator} 独立兜底。
     *
     * @param rawMetaJson 轻剪枝后的 novel body JSON 串；{@code null} / 空白 / 非法时直接跳过
     */
    public void captureForwardedNovel(long novelId, String rawMetaJson) {
        if (!StringUtils.hasText(rawMetaJson)) {
            return;
        }
        JsonNode body;
        try {
            body = objectMapper.readTree(rawMetaJson);
        } catch (Exception e) {
            log.warn("Skip forwarded novel meta {}: invalid JSON ({})", novelId, e.getMessage());
            return;
        }
        captureNovel(novelId, body, "forward");
    }

    private JsonNode parseRawJson(long workId, String kind, String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return null;
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception e) {
            log.warn("Skip {} meta {}: invalid JSON ({})", kind, workId, e.getMessage());
            return null;
        }
    }

    private void persist(WorkType type, long id, CuratedWorkMeta meta, boolean replaceSnapshot) {
        try {
            metadataStore.save(type, id, meta, replaceSnapshot);
        } catch (Exception failure) {
            log.warn("Failed to persist {} metadata {}: {}", type, id, failure.getMessage());
        }
    }
}
