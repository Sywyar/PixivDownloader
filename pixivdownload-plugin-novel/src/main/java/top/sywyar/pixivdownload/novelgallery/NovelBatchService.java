package top.sywyar.pixivdownload.novelgallery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.core.archive.ArchiveExportEntry;
import top.sywyar.pixivdownload.core.archive.ArchiveExportRequest;
import top.sywyar.pixivdownload.core.archive.ArchiveExportResult;
import top.sywyar.pixivdownload.core.archive.ArchiveExportRules;
import top.sywyar.pixivdownload.core.archive.ArchiveExportService;
import top.sywyar.pixivdownload.core.archive.ArchiveWorkDeletion;
import top.sywyar.pixivdownload.novel.metadata.NovelWorkDetails;
import top.sywyar.pixivdownload.novel.metadata.NovelWorkDetailsRepository;
import top.sywyar.pixivdownload.core.work.model.LocalWorkAsset;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.core.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.core.work.model.WorkMetadata;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.core.work.model.WorkType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 小说画廊批量操作：按显式 ID 或筛选快照解析小说集合，支持批量收藏与导出打包。
 * 行数据经 {@link WorkMetadataRepository} 批量补全、文件枚举经 {@link WorkAssetService}，
 * 不再直接依赖底层数据库与作者池实现；打包经核心归档端口执行。
 * 插画侧批量操作通过独立插件提供，两者互不依赖。
 */
@Slf4j
@PluginManagedBean
@RequiredArgsConstructor
public class NovelBatchService {

    private final NovelGalleryService novelGalleryService;
    private final WorkMetadataRepository workMetadataRepository;
    private final NovelWorkDetailsRepository novelWorkDetailsRepository;
    private final WorkAssetService workAssetService;
    private final CollectionService collectionService;
    private final ArchiveExportService archiveExportService;
    private final ObjectMapper objectMapper;

    public List<Long> resolveNovelIds(NovelBatchRequest request) {
        if (request != null && request.filterMode()) {
            List<Long> ids = novelGalleryService.findNovelIds(toNovelQuery(request.filter()));
            return ArchiveExportRules.applyExclusions(ids, request.excludeIds());
        }
        return ArchiveExportRules.applyExclusions(request == null ? null : request.ids(),
                request == null ? null : request.excludeIds());
    }

    public int collectNovels(Collection<Long> novelIds, long collectionId) {
        int changed = 0;
        for (Long id : ArchiveExportRules.normalizeIds(novelIds)) {
            if (collectionService.addNovel(collectionId, id)) {
                changed++;
            }
        }
        return changed;
    }

    public ArchiveExportResult exportNovels(Collection<Long> novelIds, String groupBy, String format,
                                            boolean deleteAfter) {
        String normalizedFormat = archiveExportService.normalizeFormat(format);
        boolean groupById = ArchiveExportRules.groupById(groupBy);
        List<Long> ids = ArchiveExportRules.normalizeIds(novelIds);
        if (ids.isEmpty()) {
            return archiveExportService.export(new ArchiveExportRequest(
                    List.of(), "novels", 0, 0, normalizedFormat, null));
        }

        List<WorkMetadata> metas = workMetadataRepository.findAll(WorkType.NOVEL, ids);
        Map<Long, NovelWorkDetails> detailsById = novelWorkDetailsRepository.findAll(
                metas.stream().map(WorkMetadata::workId).toList());
        List<ArchiveExportEntry> entries = new ArrayList<>();
        List<Map<String, Object>> manifest = new ArrayList<>();
        List<Long> exportedIds = new ArrayList<>();
        int fileCount = 0;

        for (WorkMetadata meta : metas) {
            NovelWorkDetails details = detailsById.get(meta.workId());
            if (details == null) {
                continue;
            }
            String baseDir = groupById
                    ? String.valueOf(meta.workId())
                    : "novels/" + ArchiveExportRules.authorSegment(meta.authorId(), meta.authorName())
                            + "/" + ArchiveExportRules.workSegment(meta.workId(), meta.title());
            List<Map<String, Object>> fileEntries = new ArrayList<>();
            LocalWorkAsset asset = workAssetService.findAsset(WorkType.NOVEL, meta.workId()).orElse(null);
            if (asset != null && asset.directory() != null) {
                for (WorkAssetFile file : asset.files()) {
                    String relative = asset.directory().relativize(file.path())
                            .toString().replace('\\', '/');
                    String entryName = baseDir + "/" + ArchiveExportRules.safeRelativePath(relative);
                    entries.add(ArchiveExportEntry.file(file.path(), entryName, meta.workId()));
                    fileEntries.add(fileManifest(entryName, file.path()));
                    fileCount++;
                }
            }
            manifest.add(novelManifest(meta, details, fileEntries));
            exportedIds.add(meta.workId());
        }

        if (fileCount == 0) {
            return archiveExportService.export(new ArchiveExportRequest(
                    List.of(), "novels", ids.size(), 0, normalizedFormat, null));
        }

        entries.add(ArchiveExportEntry.bytes("manifest.json", jsonBytes(manifest)));
        ArchiveWorkDeletion deletion = deleteAfter
                ? new ArchiveWorkDeletion(WorkType.NOVEL.name(), exportedIds)
                : null;
        return archiveExportService.export(new ArchiveExportRequest(
                entries, "novels", ids.size(), fileCount, normalizedFormat, deletion));
    }

    private NovelGalleryService.NovelGalleryQuery toNovelQuery(NovelBatchRequest.Filter filter) {
        Set<Long> authorIds = ArchiveExportRules.normalizeIdSet(filter.authorIds());
        if (filter.authorId() != null && filter.authorId() > 0) {
            authorIds.add(filter.authorId());
        }
        Set<Long> seriesIds = ArchiveExportRules.normalizeIdSet(filter.seriesIds());
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
                ArchiveExportRules.normalizeIdSet(filter.collectionIds()),
                ArchiveExportRules.normalizeIdSet(filter.tagIds()),
                ArchiveExportRules.normalizeIdSet(filter.notTagIds()),
                ArchiveExportRules.normalizeIdSet(filter.orTagIds()),
                authorIds,
                ArchiveExportRules.normalizeIdSet(filter.notAuthorIds()),
                ArchiveExportRules.normalizeIdSet(filter.orAuthorIds()),
                seriesIds,
                ArchiveExportRules.normalizeIdSet(filter.notSeriesIds()));
    }

    private Map<String, Object> novelManifest(WorkMetadata meta, NovelWorkDetails details,
                                               List<Map<String, Object>> files) {
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
        out.put("wordCount", details.wordCount());
        out.put("textLength", details.textLength());
        out.put("readingTimeSeconds", details.readingTimeSeconds());
        out.put("tags", tagNames(meta.tags()));
        out.put("files", files);
        return out;
    }

    private static Map<String, Object> fileManifest(String archivePath, Path originalPath) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("archivePath", archivePath);
        out.put("originalPath", originalPath == null
                ? null
                : originalPath.toAbsolutePath().normalize().toString());
        return out;
    }

    private static List<Map<String, Object>> tagNames(List<WorkTag> tags) {
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

    private byte[] jsonBytes(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            return "[]\n".getBytes(StandardCharsets.UTF_8);
        }
    }
}
