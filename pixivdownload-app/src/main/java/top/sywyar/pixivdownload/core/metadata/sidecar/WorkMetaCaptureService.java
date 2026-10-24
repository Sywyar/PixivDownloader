package top.sywyar.pixivdownload.core.metadata.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.core.metadata.ArtworkMetadataQuality;
import top.sywyar.pixivdownload.core.database.DatabaseAvailability;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;
import top.sywyar.pixivdownload.core.metadata.WorkMetadataStore;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataCapture;


/** 捕获作品快照与查询列；普通入库失败不影响已完成下载，数据库不可用则明确拒绝。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkMetaCaptureService implements WorkMetadataCapture {

    private final WorkMetaCurator curator;
    private final WorkMetadataStore metadataStore;
    private final ObjectMapper objectMapper;
    private final DatabaseAvailability database;

    /**
     * 捕获插画 meta：归一化后原子更新数据库快照与查询列。
     *
     * @param illustBody {@code /ajax/illust/{id}} 的 body；为 {@code null} 时直接跳过（无可捕获）
     * @param pagesBody  {@code /ajax/illust/{id}/pages} 的 body（逐页尺寸）；无逐页时传 {@code null}
     * @param source     捕获来源（{@code schedule}/{@code forward}/{@code backfill}）
     */
    public void captureArtwork(long artworkId, JsonNode illustBody, JsonNode pagesBody, String source) {
        database.requireAvailable();
        if (illustBody == null || !illustBody.isObject()) {
            return;
        }
        CuratedWorkMeta curated;
        try {
            curated = curator.curateArtwork(artworkId, illustBody, pagesBody, source);
        } catch (RuntimeException e) {
            database.requireAvailable();
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
     * 捕获小说 meta：归一化后写 {@code upload_time} 列投影（小说 {@code is_original} 列在 insert 时已写）及数据库快照。
     *
     * @param novelBody {@code /ajax/novel/{id}} 的 body；为 {@code null} 时直接跳过
     */
    public void captureNovel(long novelId, JsonNode novelBody, String source) {
        database.requireAvailable();
        if (novelBody == null || !novelBody.isObject()) {
            return;
        }
        CuratedWorkMeta curated;
        try {
            curated = curator.curateNovel(novelId, novelBody, source);
        } catch (RuntimeException e) {
            database.requireAvailable();
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
        database.requireAvailable();
        if (type == null) {
            return;
        }
        switch (type) {
            case ARTWORK -> captureArtworkJson(workId, workJson, supplementalJson, source);
            case NOVEL -> captureNovelJson(workId, workJson, source);
        }
        database.requireAvailable();
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
        database.requireAvailable();
        try {
            metadataStore.save(type, id, meta, replaceSnapshot);
            database.requireAvailable();
        } catch (DatabaseUnavailableException unavailable) {
            throw unavailable;
        } catch (Exception failure) {
            database.requireAvailable();
            log.warn("Failed to persist {} metadata {}: {}", type, id, failure.getMessage());
        }
    }
}
