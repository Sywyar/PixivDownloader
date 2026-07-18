package top.sywyar.pixivdownload.core.metadata.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRow;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 作品 meta 捕获落地服务：把一份捕获到的 Pixiv 原始 body 归一化（{@link WorkMetaCurator}）
 * 后，写出权威 sidecar（{@link WorkSidecarStore}）并刷新可查询的列投影（{@code upload_time} /
 * {@code is_original}）。下载已完成、作品行已落库后调用（计划任务在下载成功后旁路调用）。
 *
 * <p><b>一致性模型</b>：sidecar 是元数据的权威落点，{@code upload_time} /
 * {@code is_original} 列是可重建投影。两者各自 best-effort、互不阻断：任一写失败仅记日志，由下次下载
 * 刷新或后续历史回填自愈，<b>绝不</b>反报下载失败（下载已成功，反报会留下被「跳过已下载」挡住的孤儿）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkMetaCaptureService {

    private final WorkMetaCurator curator;
    private final WorkSidecarStore sidecarStore;
    private final PixivDatabase pixivDatabase;
    private final NovelMetadataRepository novelMetadataRepository;
    private final ArtworkFileLocator artworkFileLocator;
    private final ObjectMapper objectMapper;

    /**
     * 捕获插画 meta：归一化后写列投影 + sidecar。
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
        // 列投影（可重建）：与 sidecar 各自独立，超总大小上限被拒时仍写。
        try {
            pixivDatabase.updateArtworkUploadMeta(artworkId, curated.uploadTime(), curated.isOriginal());
        } catch (RuntimeException e) {
            log.warn("Failed to write artwork upload-meta columns {}: {}", artworkId, e.getMessage());
        }
        // sidecar（权威）：归一化结果超总大小上限被拒（无 document）时不落半成品，仅 warn（列投影已写、不反报下载失败）。
        if (!curated.hasDocument()) {
            log.warn("Skip artwork sidecar {}: curated meta exceeds size cap, column projection kept", artworkId);
            return;
        }
        ArtworkRecord rec = pixivDatabase.getArtwork(artworkId);
        if (rec == null) {
            return;
        }
        String directory = artworkFileLocator.resolveArtworkDirectory(rec);
        writeSidecar(directory, artworkId, curated, "artwork");
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
     * 捕获小说 meta：归一化后写 {@code upload_time} 列投影（小说 {@code is_original} 列在 insert 时已写）+ sidecar。
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
        try {
            novelMetadataRepository.updateNovelUploadTime(novelId, curated.uploadTime());
        } catch (RuntimeException e) {
            log.warn("Failed to write novel upload_time column {}: {}", novelId, e.getMessage());
        }
        // sidecar（权威）：归一化结果超总大小上限被拒（无 document）时不落半成品，仅 warn（列投影已写、不反报下载失败）。
        if (!curated.hasDocument()) {
            log.warn("Skip novel sidecar {}: curated meta exceeds size cap, column projection kept", novelId);
            return;
        }
        NovelMetadataRow rec = novelMetadataRepository.getNovel(novelId);
        if (rec == null) {
            return;
        }
        writeSidecar(rec.folder(), novelId, curated, "novel");
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

    private void writeSidecar(String directory, long workId, CuratedWorkMeta curated, String kind) {
        if (!StringUtils.hasText(directory)) {
            log.warn("Skip {} sidecar {}: no resolvable directory", kind, workId);
            return;
        }
        try {
            Path dir = Paths.get(directory);
            sidecarStore.write(dir, workId, curated.document());
        } catch (Exception e) {
            log.warn("Failed to write {} sidecar {}: {}", kind, workId, e.getMessage());
        }
    }
}
