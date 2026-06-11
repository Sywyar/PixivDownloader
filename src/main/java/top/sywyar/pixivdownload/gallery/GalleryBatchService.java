package top.sywyar.pixivdownload.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.download.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.quota.ArchiveExportSupport;
import top.sywyar.pixivdownload.quota.ArchiveExportSupport.ExportResult;
import top.sywyar.pixivdownload.quota.MultiModeConfig;
import top.sywyar.pixivdownload.quota.UserQuotaService;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 插画画廊批量操作：按显式 ID 或筛选快照解析作品集合，支持批量收藏与导出打包。
 * 小说侧的对应逻辑在 {@code novel.NovelBatchService}，两者互不依赖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GalleryBatchService {

    private static final Set<String> ALLOWED_FORMATS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    private final GalleryService galleryService;
    private final PixivDatabase pixivDatabase;
    private final ArtworkFileLocator artworkFileLocator;
    private final AuthorService authorService;
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

        List<ArtworkRecord> fetched = pixivDatabase.getArtworks(ids);
        Map<Long, ArtworkRecord> byId = new HashMap<>(fetched.size());
        Set<Long> authorIds = new HashSet<>();
        for (ArtworkRecord record : fetched) {
            byId.put(record.artworkId(), record);
            if (record.authorId() != null && record.authorId() > 0) {
                authorIds.add(record.authorId());
            }
        }
        Map<Long, String> authorNames = authorService.getAuthorNames(authorIds);
        Map<Long, List<TagDto>> tagsByArtwork = pixivDatabase.getArtworkTags(ids);
        List<UserQuotaService.ArchiveItem> items = new ArrayList<>();
        List<Map<String, Object>> manifest = new ArrayList<>();
        int fileCount = 0;

        for (Long id : ids) {
            ArtworkRecord record = byId.get(id);
            if (record == null) continue;
            String authorName = record.authorId() == null ? null : authorNames.get(record.authorId());
            String baseDir = groupById
                    ? String.valueOf(record.artworkId())
                    : "artworks/" + ArchiveExportSupport.authorSegment(record.authorId(), authorName)
                            + "/" + ArchiveExportSupport.workSegment(record.artworkId(), record.title());
            List<Map<String, Object>> fileEntries = new ArrayList<>();
            int pages = Math.max(1, record.count());
            for (int page = 0; page < pages; page++) {
                File file = artworkFileLocator.resolveImageFile(record, page);
                if (file == null || !file.isFile()) continue;
                String entryName = baseDir + "/"
                        + ArchiveExportSupport.safeSegment(file.getName(), "page-" + (page + 1));
                items.add(UserQuotaService.ArchiveItem.file(file.toPath(), entryName, record.artworkId()));
                fileEntries.add(ArchiveExportSupport.fileManifest(entryName, file.toPath()));
                fileCount++;
            }
            manifest.add(artworkManifest(record, authorName, tagsByArtwork.get(record.artworkId()), fileEntries));
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

    private Map<String, Object> artworkManifest(ArtworkRecord record, String authorName,
                                                List<TagDto> tags, List<Map<String, Object>> files) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "artwork");
        out.put("id", record.artworkId());
        out.put("title", record.title());
        out.put("authorId", record.authorId());
        out.put("authorName", authorName);
        out.put("seriesId", record.seriesId());
        out.put("seriesOrder", record.seriesOrder());
        out.put("xRestrict", record.xRestrict());
        out.put("isAi", record.isAi());
        out.put("time", record.time());
        out.put("tags", ArchiveExportSupport.tagNames(tags));
        out.put("files", files);
        return out;
    }

    private long archiveExpireSeconds() {
        return (long) multiModeConfig.getQuota().getArchiveExpireMinutes() * 60;
    }
}
