package top.sywyar.pixivdownload.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.plugin.api.LocalWorkAsset;
import top.sywyar.pixivdownload.plugin.api.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.WorkAssetFile;
import top.sywyar.pixivdownload.plugin.api.WorkAssetService;
import top.sywyar.pixivdownload.plugin.api.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.WorkTag;
import top.sywyar.pixivdownload.plugin.api.WorkType;
import top.sywyar.pixivdownload.quota.ArchiveExportSupport;
import top.sywyar.pixivdownload.quota.ArchiveExportSupport.ExportResult;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.quota.UserQuotaService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 插画画廊批量操作：按显式 ID 或筛选快照解析作品集合，支持批量收藏与导出打包。
 * 行数据经 {@link WorkMetadataRepository} 批量补全、文件枚举经 {@link WorkAssetService}，
 * 不再直接依赖底层数据库与下载侧文件定位；打包仍走核心 quota 打包能力。
 * 小说侧的对应逻辑在 {@code novel.NovelBatchService}，两者互不依赖。
 */
@Slf4j
@PluginManagedBean
@RequiredArgsConstructor
public class GalleryBatchService {

    private static final Set<String> ALLOWED_FORMATS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    private final GalleryService galleryService;
    private final WorkMetadataRepository workMetadataRepository;
    private final WorkAssetService workAssetService;
    private final CollectionService collectionService;
    private final UserQuotaService userQuotaService;
    private final MultiModeConfig multiModeConfig;
    private final ObjectMapper objectMapper;

    public List<Long> resolveArtworkIds(ArtworkBatchRequest request) {
        if (request != null && request.filterMode()) {
            List<Long> ids = galleryService.findArtworkIds(toGalleryQuery(request.filter()));
            return ArchiveExportSupport.applyExclusions(ids, request.excludeIds());
        }
        return ArchiveExportSupport.applyExclusions(request == null ? null : request.ids(),
                request == null ? null : request.excludeIds());
    }

    public int collectArtworks(Collection<Long> artworkIds, long collectionId) {
        int changed = 0;
        for (Long id : ArchiveExportSupport.normalizeIds(artworkIds)) {
            if (collectionService.addArtwork(collectionId, id)) {
                changed++;
            }
        }
        return changed;
    }

    public ExportResult exportArtworks(Collection<Long> artworkIds, String groupBy, String format,
                                       boolean deleteAfter) {
        ArchiveExportSupport.normalizeFormat(format);
        boolean groupById = ArchiveExportSupport.groupById(groupBy);
        List<Long> ids = ArchiveExportSupport.normalizeIds(artworkIds);
        if (ids.isEmpty()) {
            return ExportResult.empty();
        }

        List<WorkMetadata> metas = workMetadataRepository.findAll(WorkType.ARTWORK, ids);
        List<UserQuotaService.ArchiveItem> items = new ArrayList<>();
        List<Map<String, Object>> manifest = new ArrayList<>();
        int fileCount = 0;

        for (WorkMetadata meta : metas) {
            String baseDir = groupById
                    ? String.valueOf(meta.workId())
                    : "artworks/" + ArchiveExportSupport.authorSegment(meta.authorId(), meta.authorName())
                            + "/" + ArchiveExportSupport.workSegment(meta.workId(), meta.title());
            List<Map<String, Object>> fileEntries = new ArrayList<>();
            List<WorkAssetFile> files = workAssetService.findAsset(WorkType.ARTWORK, meta.workId())
                    .map(LocalWorkAsset::files)
                    .orElse(List.of());
            for (WorkAssetFile file : files) {
                String entryName = baseDir + "/" + ArchiveExportSupport.safeSegment(
                        file.path().getFileName().toString(), "page-" + (file.page() + 1));
                items.add(UserQuotaService.ArchiveItem.file(file.path(), entryName, meta.workId()));
                fileEntries.add(ArchiveExportSupport.fileManifest(entryName, file.path()));
                fileCount++;
            }
            manifest.add(artworkManifest(meta, fileEntries));
        }

        if (fileCount == 0) {
            return ExportResult.empty(ids.size());
        }

        items.add(UserQuotaService.ArchiveItem.bytes("manifest.json",
                ArchiveExportSupport.jsonBytes(objectMapper, manifest)));
        Runnable afterReady = deleteAfter ? () -> galleryService.deleteArtworks(ids) : null;
        String token = userQuotaService.triggerAdminFileArchive(items, "artworks", ids.size(), afterReady);
        return new ExportResult(token, archiveExpireSeconds(), ids.size(), fileCount);
    }

    private GalleryQuery toGalleryQuery(ArtworkBatchRequest.Filter filter) {
        List<Long> authorIds = new ArrayList<>(ArchiveExportSupport.normalizeIds(filter.authorIds()));
        if (filter.authorId() != null && filter.authorId() > 0 && !authorIds.contains(filter.authorId())) {
            authorIds.add(filter.authorId());
        }
        List<Long> seriesIds = new ArrayList<>(ArchiveExportSupport.normalizeIds(filter.seriesIds()));
        if (filter.seriesId() != null && filter.seriesId() > 0 && !seriesIds.contains(filter.seriesId())) {
            seriesIds.add(filter.seriesId());
        }
        GalleryQuery query = GalleryQuery.normalize(
                0,
                200,
                filter.sort(),
                filter.order(),
                filter.search(),
                filter.r18(),
                filter.ai(),
                normalizeFormats(filter.formats()),
                ArchiveExportSupport.normalizeIds(filter.collectionIds()),
                ArchiveExportSupport.normalizeIds(filter.tagIds()),
                ArchiveExportSupport.normalizeIds(filter.notTagIds()),
                ArchiveExportSupport.normalizeIds(filter.orTagIds()),
                authorIds,
                ArchiveExportSupport.normalizeIds(filter.notAuthorIds()),
                ArchiveExportSupport.normalizeIds(filter.orAuthorIds()),
                seriesIds,
                ArchiveExportSupport.normalizeIds(filter.notSeriesIds()));
        query.setSearchType(GalleryQuery.normalizeSearchType(filter.searchType()));
        return query;
    }

    private List<String> normalizeFormats(Collection<String> formats) {
        if (formats == null || formats.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String format : formats) {
            if (!StringUtils.hasText(format)) continue;
            String normalized = format.trim().toLowerCase(Locale.ROOT);
            if (ALLOWED_FORMATS.contains(normalized)) {
                out.add(normalized);
            }
        }
        return out.isEmpty() ? null : new ArrayList<>(out);
    }

    private Map<String, Object> artworkManifest(WorkMetadata meta, List<Map<String, Object>> files) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "artwork");
        out.put("id", meta.workId());
        out.put("title", meta.title());
        out.put("authorId", meta.authorId());
        out.put("authorName", meta.authorName());
        out.put("seriesId", meta.seriesId());
        out.put("seriesOrder", meta.seriesOrder());
        out.put("xRestrict", meta.xRestrict());
        out.put("isAi", meta.isAi());
        out.put("time", meta.downloadTime());
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
