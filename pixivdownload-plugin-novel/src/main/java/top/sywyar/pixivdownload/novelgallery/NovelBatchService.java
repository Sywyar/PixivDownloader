package top.sywyar.pixivdownload.novelgallery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import top.sywyar.pixivdownload.novel.export.NovelExportService;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService.NovelFormat;
import top.sywyar.pixivdownload.translation.api.TranslationVariant;
import top.sywyar.pixivdownload.core.collection.WorkCollectionMembership;
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
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * 小说画廊批量操作：按显式 ID 或筛选快照解析小说集合，支持批量收藏与导出打包。
 * 行数据经 {@link WorkMetadataRepository} 批量补全、文件枚举经 {@link WorkAssetService}，
 * 不再直接依赖底层数据库与作者池实现；打包经核心归档端口执行。
 * 插画侧批量操作通过独立插件提供，两者互不依赖。
 */
@PluginManagedBean
@RequiredArgsConstructor
public class NovelBatchService {
    static final long MAX_GENERATED_BYTES = 64L * 1024 * 1024;

    private final NovelGalleryService novelGalleryService;
    private final WorkMetadataRepository workMetadataRepository;
    private final NovelWorkDetailsRepository novelWorkDetailsRepository;
    private final WorkAssetService workAssetService;
    private final WorkCollectionMembership workCollectionMembership;
    private final ArchiveExportService archiveExportService;
    private final ObjectMapper objectMapper;
    private final NovelExportService novelExportService;

    public record ExportOptions(List<String> variants) {}

    public static class ExportRejected extends RuntimeException {
        private final String reason;
        public ExportRejected(String reason) { super(reason); this.reason = reason; }
        public String reason() { return reason; }
    }

    public ExportOptions exportOptions(Collection<Long> novelIds) {
        List<Long> ids = ArchiveExportRules.normalizeIds(novelIds);
        Map<Long, NovelWorkDetails> details = novelWorkDetailsRepository.findAll(ids);
        Set<String> common = null;
        for (Long id : ids) {
            NovelWorkDetails novel = details.get(id);
            if (novel == null) return new ExportOptions(List.of());
            if (common == null) common = new LinkedHashSet<>(novel.translatedLanguages());
            else common.retainAll(novel.translatedLanguages());
        }
        return new ExportOptions(common == null ? List.of() : List.copyOf(common));
    }

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
            if (workCollectionMembership.addWork(WorkType.NOVEL, collectionId, id)) {
                changed++;
            }
        }
        return changed;
    }

    public ArchiveExportResult exportNovels(Collection<Long> novelIds, String groupBy, String format,
                                            boolean deleteAfter, String variant) throws IOException {
        String key;
        try { key = variant == null || variant.isBlank() ? "" : TranslationVariant.parse(variant).key(); }
        catch (IllegalArgumentException invalid) { throw new ExportRejected("invalid-request"); }
        if (!key.isEmpty() && deleteAfter) throw new ExportRejected("invalid-request");
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
        if (!key.isEmpty() && (metas.size() != ids.size() || ids.stream().anyMatch(id ->
                !detailsById.containsKey(id) || !detailsById.get(id).translatedLanguages().contains(key)))) {
            throw new ExportRejected("unavailable");
        }
        List<ArchiveExportEntry> entries = new ArrayList<>();
        List<Map<String, Object>> manifest = new ArrayList<>();
        List<Long> exportedIds = new ArrayList<>();
        int fileCount = 0;
        long generatedBytes = 0;

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
            if (!key.isEmpty()) {
                Set<NovelFormat> documentFormats = new LinkedHashSet<>();
                if (asset != null) for (WorkAssetFile file : asset.files()) {
                    NovelFormat documentFormat = documentFormat(file);
                    if (documentFormat != null) documentFormats.add(documentFormat);
                }
                if (documentFormats.isEmpty()) documentFormats.add(NovelFormat.EPUB);
                for (NovelFormat documentFormat : documentFormats) {
                    var rendered = novelExportService.export(meta.workId(), documentFormat, key);
                    if (rendered == null) throw new ExportRejected("unavailable");
                    generatedBytes += rendered.content().length;
                    if (generatedBytes > MAX_GENERATED_BYTES) throw new ExportRejected("too-large");
                    String entryName = baseDir + "/" + rendered.filename();
                    entries.add(new ArchiveExportEntry(null, entryName, rendered.content(), meta.workId()));
                    fileEntries.add(fileManifest(entryName, null));
                    fileCount++;
                }
            }
            if (asset != null && asset.directory() != null) {
                for (WorkAssetFile file : asset.files()) {
                    if (!key.isEmpty() && documentFormat(file) != null) continue;
                    String relative = asset.directory().relativize(file.path())
                            .toString().replace('\\', '/');
                    String entryName = baseDir + "/" + ArchiveExportRules.safeRelativePath(relative);
                    entries.add(ArchiveExportEntry.file(file.path(), entryName, meta.workId()));
                    fileEntries.add(fileManifest(entryName, file.path()));
                    fileCount++;
                }
            }
            var workManifest = novelManifest(meta, details, fileEntries);
            if (!key.isEmpty()) workManifest.put("translationVariant", key);
            manifest.add(workManifest);
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

    private static NovelFormat documentFormat(WorkAssetFile file) {
        String name = file.path().getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        for (NovelFormat format : NovelFormat.values()) {
            if (name.endsWith("." + format.ext())) return format;
        }
        return null;
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
                ArchiveExportRules.normalizeIdSet(filter.notSeriesIds()), null, filter.uploadedFrom(), filter.uploadedBefore(), filter.downloadedFrom(), filter.downloadedBefore());
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
        out.put("uploadTime", meta.uploadTime());
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
