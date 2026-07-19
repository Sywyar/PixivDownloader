package top.sywyar.pixivdownload.gallery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.core.archive.ArchiveExportEntry;
import top.sywyar.pixivdownload.core.archive.ArchiveExportRequest;
import top.sywyar.pixivdownload.core.archive.ArchiveExportResult;
import top.sywyar.pixivdownload.core.archive.ArchiveExportRules;
import top.sywyar.pixivdownload.core.archive.ArchiveExportService;
import top.sywyar.pixivdownload.core.archive.ArchiveWorkDeletion;
import top.sywyar.pixivdownload.core.collection.ArtworkCollectionMembership;
import top.sywyar.pixivdownload.gallery.web.GalleryWorkQueryFactory;
import top.sywyar.pixivdownload.core.work.model.LocalWorkAsset;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.core.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.core.work.model.WorkMetadata;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
    private final ArtworkCollectionMembership collectionMembership;
    private final ArchiveExportService archiveExportService;
    private final ObjectMapper objectMapper;

    public List<Long> resolveArtworkIds(ArtworkBatchRequest request) {
        if (request != null && request.filterMode()) {
            List<Long> ids = galleryService.findArtworkIds(toGalleryQuery(request.filter()));
            return ArchiveExportRules.applyExclusions(ids, request.excludeIds());
        }
        return ArchiveExportRules.applyExclusions(request == null ? null : request.ids(),
                request == null ? null : request.excludeIds());
    }

    public int collectArtworks(Collection<Long> artworkIds, long collectionId) {
        int changed = 0;
        for (Long id : ArchiveExportRules.normalizeIds(artworkIds)) {
            if (collectionMembership.addArtwork(collectionId, id)) {
                changed++;
            }
        }
        return changed;
    }

    public ArchiveExportResult exportArtworks(Collection<Long> artworkIds, String groupBy, String format,
                                              boolean deleteAfter) {
        String normalizedFormat = archiveExportService.normalizeFormat(format);
        boolean groupById = ArchiveExportRules.groupById(groupBy);
        List<Long> ids = ArchiveExportRules.normalizeIds(artworkIds);
        if (ids.isEmpty()) {
            return archiveExportService.export(new ArchiveExportRequest(
                    List.of(), "artworks", 0, 0, normalizedFormat, null));
        }

        List<WorkMetadata> metas = workMetadataRepository.findAll(WorkType.ARTWORK, ids);
        List<ArchiveExportEntry> entries = new ArrayList<>();
        List<Map<String, Object>> manifest = new ArrayList<>();
        int fileCount = 0;

        for (WorkMetadata meta : metas) {
            String baseDir = groupById
                    ? String.valueOf(meta.workId())
                    : "artworks/" + ArchiveExportRules.authorSegment(meta.authorId(), meta.authorName())
                            + "/" + ArchiveExportRules.workSegment(meta.workId(), meta.title());
            List<Map<String, Object>> fileEntries = new ArrayList<>();
            List<WorkAssetFile> files = workAssetService.findAsset(WorkType.ARTWORK, meta.workId())
                    .map(LocalWorkAsset::files)
                    .orElse(List.of());
            for (WorkAssetFile file : files) {
                String entryName = baseDir + "/" + ArchiveExportRules.safeSegment(
                        file.path().getFileName().toString(), "page-" + (file.page() + 1));
                entries.add(ArchiveExportEntry.file(file.path(), entryName, meta.workId()));
                fileEntries.add(fileManifest(entryName, file.path()));
                fileCount++;
            }
            manifest.add(artworkManifest(meta, fileEntries));
        }

        if (fileCount == 0) {
            return archiveExportService.export(new ArchiveExportRequest(
                    List.of(), "artworks", ids.size(), 0, normalizedFormat, null));
        }

        entries.add(ArchiveExportEntry.bytes("manifest.json", jsonBytes(manifest)));
        return archiveExportService.export(new ArchiveExportRequest(
                entries, "artworks", ids.size(), fileCount, normalizedFormat,
                deleteAfter ? new ArchiveWorkDeletion(WorkType.ARTWORK.name(), ids) : null));
    }

    private WorkQuery toGalleryQuery(ArtworkBatchRequest.Filter filter) {
        List<Long> authorIds = new ArrayList<>(ArchiveExportRules.normalizeIds(filter.authorIds()));
        if (filter.authorId() != null && filter.authorId() > 0 && !authorIds.contains(filter.authorId())) {
            authorIds.add(filter.authorId());
        }
        List<Long> seriesIds = new ArrayList<>(ArchiveExportRules.normalizeIds(filter.seriesIds()));
        if (filter.seriesId() != null && filter.seriesId() > 0 && !seriesIds.contains(filter.seriesId())) {
            seriesIds.add(filter.seriesId());
        }
        return GalleryWorkQueryFactory.create(
                0,
                200,
                filter.sort(),
                filter.order(),
                filter.search(),
                filter.searchType(),
                filter.r18(),
                filter.ai(),
                normalizeFormats(filter.formats()),
                ArchiveExportRules.normalizeIds(filter.collectionIds()),
                ArchiveExportRules.normalizeIds(filter.tagIds()),
                ArchiveExportRules.normalizeIds(filter.notTagIds()),
                ArchiveExportRules.normalizeIds(filter.orTagIds()),
                authorIds,
                ArchiveExportRules.normalizeIds(filter.notAuthorIds()),
                ArchiveExportRules.normalizeIds(filter.orAuthorIds()),
                seriesIds,
                ArchiveExportRules.normalizeIds(filter.notSeriesIds()),
                null);
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
        out.put("tags", tagManifest(meta.tags()));
        out.put("files", files);
        return out;
    }

    private static List<Map<String, Object>> tagManifest(List<WorkTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(tags.size());
        for (WorkTag tag : tags) {
            if (tag == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", tag.tagId());
            item.put("name", tag.name());
            item.put("translatedName", tag.translatedName());
            out.add(item);
        }
        return out;
    }

    private static Map<String, Object> fileManifest(String archivePath, Path originalPath) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("archivePath", archivePath);
        out.put("originalPath", originalPath == null
                ? null : originalPath.toAbsolutePath().normalize().toString());
        return out;
    }

    private byte[] jsonBytes(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            return "[]\n".getBytes(StandardCharsets.UTF_8);
        }
    }
}
