package top.sywyar.pixivdownload.novel;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.novel.request.NovelBatchRequest;
import top.sywyar.pixivdownload.plugin.api.LocalWorkAsset;
import top.sywyar.pixivdownload.plugin.api.WorkAssetFile;
import top.sywyar.pixivdownload.plugin.api.WorkAssetService;
import top.sywyar.pixivdownload.plugin.api.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.WorkTag;
import top.sywyar.pixivdownload.plugin.api.WorkType;
import top.sywyar.pixivdownload.quota.ArchiveExportSupport;
import top.sywyar.pixivdownload.quota.ArchiveExportSupport.ExportResult;
import top.sywyar.pixivdownload.quota.UserQuotaService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 小说画廊批量操作：按显式 ID 或筛选快照解析小说集合，支持批量收藏与导出打包。
 * 行数据经 {@link WorkMetadataRepository} 批量补全、文件枚举经 {@link WorkAssetService}，
 * 不再直接依赖底层数据库与作者池实现；打包仍走核心 quota 打包能力。
 * 插画侧的对应逻辑在 {@code gallery.GalleryBatchService}，两者互不依赖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelBatchService {

    private final NovelGalleryService novelGalleryService;
    private final WorkMetadataRepository workMetadataRepository;
    private final WorkAssetService workAssetService;
    private final CollectionService collectionService;
    private final UserQuotaService userQuotaService;
    private final MultiModeConfig multiModeConfig;
    private final ObjectMapper objectMapper;

    public List<Long> resolveNovelIds(NovelBatchRequest request) {
        if (request != null && request.filterMode()) {
            List<Long> ids = novelGalleryService.findNovelIds(toNovelQuery(request.filter()));
            return ArchiveExportSupport.applyExclusions(ids, request.excludeIds());
        }
        return ArchiveExportSupport.applyExclusions(request == null ? null : request.ids(),
                request == null ? null : request.excludeIds());
    }

    public int collectNovels(Collection<Long> novelIds, long collectionId) {
        int changed = 0;
        for (Long id : ArchiveExportSupport.normalizeIds(novelIds)) {
            if (collectionService.addNovel(collectionId, id)) {
                changed++;
            }
        }
        return changed;
    }

    public ExportResult exportNovels(Collection<Long> novelIds, String groupBy, String format,
                                     boolean deleteAfter) {
        ArchiveExportSupport.normalizeFormat(format);
        boolean groupById = ArchiveExportSupport.groupById(groupBy);
        List<Long> ids = ArchiveExportSupport.normalizeIds(novelIds);
        if (ids.isEmpty()) {
            return ExportResult.empty();
        }

        List<WorkMetadata> metas = workMetadataRepository.findAll(WorkType.NOVEL, ids);
        List<UserQuotaService.ArchiveItem> items = new ArrayList<>();
        List<Map<String, Object>> manifest = new ArrayList<>();
        int fileCount = 0;

        for (WorkMetadata meta : metas) {
            String baseDir = groupById
                    ? String.valueOf(meta.workId())
                    : "novels/" + ArchiveExportSupport.authorSegment(meta.authorId(), meta.authorName())
                            + "/" + ArchiveExportSupport.workSegment(meta.workId(), meta.title());
            List<Map<String, Object>> fileEntries = new ArrayList<>();
            LocalWorkAsset asset = workAssetService.findAsset(WorkType.NOVEL, meta.workId()).orElse(null);
            if (asset != null && asset.directory() != null) {
                for (WorkAssetFile file : asset.files()) {
                    String relative = asset.directory().relativize(file.path())
                            .toString().replace('\\', '/');
                    String entryName = baseDir + "/" + ArchiveExportSupport.safeRelativePath(relative);
                    items.add(UserQuotaService.ArchiveItem.file(file.path(), entryName, meta.workId()));
                    fileEntries.add(ArchiveExportSupport.fileManifest(entryName, file.path()));
                    fileCount++;
                }
            }
            manifest.add(novelManifest(meta, fileEntries));
        }

        if (fileCount == 0) {
            return ExportResult.empty(ids.size());
        }

        items.add(UserQuotaService.ArchiveItem.bytes("manifest.json",
                ArchiveExportSupport.jsonBytes(objectMapper, manifest)));
        Runnable afterReady = deleteAfter ? () -> novelGalleryService.deleteNovels(ids) : null;
        String token = userQuotaService.triggerAdminFileArchive(items, "novels", ids.size(), afterReady);
        return new ExportResult(token, archiveExpireSeconds(), ids.size(), fileCount);
    }

    private NovelGalleryService.NovelGalleryQuery toNovelQuery(NovelBatchRequest.Filter filter) {
        Set<Long> authorIds = ArchiveExportSupport.normalizeIdSet(filter.authorIds());
        if (filter.authorId() != null && filter.authorId() > 0) {
            authorIds.add(filter.authorId());
        }
        Set<Long> seriesIds = ArchiveExportSupport.normalizeIdSet(filter.seriesIds());
        if (filter.seriesId() != null && filter.seriesId() > 0) {
            seriesIds.add(filter.seriesId());
        }
        return new NovelGalleryService.NovelGalleryQuery(
                0,
                200,
                filter.sort(),
                filter.order(),
                filter.search(),
                NovelGalleryService.normalizeSearchType(filter.searchType()),
                filter.r18(),
                filter.ai(),
                ArchiveExportSupport.normalizeIdSet(filter.collectionIds()),
                ArchiveExportSupport.normalizeIdSet(filter.tagIds()),
                ArchiveExportSupport.normalizeIdSet(filter.notTagIds()),
                ArchiveExportSupport.normalizeIdSet(filter.orTagIds()),
                authorIds,
                ArchiveExportSupport.normalizeIdSet(filter.notAuthorIds()),
                ArchiveExportSupport.normalizeIdSet(filter.orAuthorIds()),
                seriesIds,
                ArchiveExportSupport.normalizeIdSet(filter.notSeriesIds()));
    }

    private Map<String, Object> novelManifest(WorkMetadata meta, List<Map<String, Object>> files) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "novel");
        out.put("id", meta.workId());
        out.put("title", meta.title());
        out.put("authorId", meta.authorId());
        out.put("authorName", meta.authorName());
        out.put("seriesId", meta.seriesId());
        out.put("seriesOrder", meta.seriesOrder());
        out.put("xRestrict", meta.xRestrict());
        out.put("isAi", meta.isAi());
        out.put("time", meta.downloadTime());
        out.put("wordCount", meta.novel().wordCount());
        out.put("textLength", meta.novel().textLength());
        out.put("readingTimeSeconds", meta.novel().readingTimeSeconds());
        out.put("tags", ArchiveExportSupport.tagNames(toTagDtos(meta.tags())));
        out.put("files", files);
        return out;
    }

    private static List<TagDto> toTagDtos(List<WorkTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<TagDto> out = new ArrayList<>(tags.size());
        for (WorkTag tag : tags) {
            out.add(new TagDto(tag.tagId(), tag.name(), tag.translatedName()));
        }
        return out;
    }

    private long archiveExpireSeconds() {
        return (long) multiModeConfig.getQuota().getArchiveExpireMinutes() * 60;
    }
}
