package top.sywyar.pixivdownload.novel;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.request.NovelBatchRequest;
import top.sywyar.pixivdownload.quota.ArchiveExportSupport;
import top.sywyar.pixivdownload.quota.ArchiveExportSupport.ExportResult;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.quota.UserQuotaService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 小说画廊批量操作：按显式 ID 或筛选快照解析小说集合，支持批量收藏与导出打包。
 * 插画侧的对应逻辑在 {@code gallery.GalleryBatchService}，两者互不依赖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelBatchService {

    private final NovelGalleryService novelGalleryService;
    private final NovelDatabase novelDatabase;
    private final AuthorService authorService;
    private final CollectionService collectionService;
    private final UserQuotaService userQuotaService;
    private final MultiModeConfig multiModeConfig;
    private final DownloadConfig downloadConfig;
    private final ObjectMapper objectMapper;
    private final AppMessages messages;

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

        Set<Long> authorIds = new HashSet<>();
        List<NovelRecord> records = new ArrayList<>();
        for (Long id : ids) {
            NovelRecord record = novelDatabase.getNovel(id);
            if (record == null) continue;
            records.add(record);
            if (record.authorId() != null && record.authorId() > 0) {
                authorIds.add(record.authorId());
            }
        }
        Map<Long, String> authorNames = authorService.getAuthorNames(authorIds);
        List<UserQuotaService.ArchiveItem> items = new ArrayList<>();
        List<Map<String, Object>> manifest = new ArrayList<>();
        int fileCount = 0;

        for (NovelRecord record : records) {
            String authorName = record.authorId() == null ? null : authorNames.get(record.authorId());
            String baseDir = groupById
                    ? String.valueOf(record.novelId())
                    : "novels/" + ArchiveExportSupport.authorSegment(record.authorId(), authorName)
                            + "/" + ArchiveExportSupport.workSegment(record.novelId(), record.title());
            List<Map<String, Object>> fileEntries = new ArrayList<>();
            Path folder = safeNovelDirectory(record);
            if (folder != null) {
                try (var stream = Files.walk(folder)) {
                    List<Path> files = stream
                            .filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(Path::toString))
                            .toList();
                    for (Path file : files) {
                        String relative = folder.relativize(file).toString().replace('\\', '/');
                        String entryName = baseDir + "/" + ArchiveExportSupport.safeRelativePath(relative);
                        items.add(UserQuotaService.ArchiveItem.file(file, entryName, record.novelId()));
                        fileEntries.add(ArchiveExportSupport.fileManifest(entryName, file));
                        fileCount++;
                    }
                } catch (IOException e) {
                    log.warn(messages.getForLog("archive.log.export.novel-folder.unreadable",
                            record.novelId(), folder), e);
                }
            }
            manifest.add(novelManifest(record, authorName, novelDatabase.getNovelTags(record.novelId()), fileEntries));
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

    private Map<String, Object> novelManifest(NovelRecord record, String authorName,
                                              List<TagDto> tags, List<Map<String, Object>> files) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "novel");
        out.put("id", record.novelId());
        out.put("title", record.title());
        out.put("authorId", record.authorId());
        out.put("authorName", authorName);
        out.put("seriesId", record.seriesId());
        out.put("seriesOrder", record.seriesOrder());
        out.put("xRestrict", record.xRestrict());
        out.put("isAi", record.isAi());
        out.put("time", record.time());
        out.put("wordCount", record.wordCount());
        out.put("textLength", record.textLength());
        out.put("readingTimeSeconds", record.readingTimeSeconds());
        out.put("tags", ArchiveExportSupport.tagNames(tags));
        out.put("files", files);
        return out;
    }

    private Path safeNovelDirectory(NovelRecord record) {
        if (!StringUtils.hasText(record.folder())) {
            return null;
        }
        Path dir;
        try {
            dir = Paths.get(record.folder()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        if (!Files.isDirectory(dir) || dir.getNameCount() < 1 || dir.equals(dir.getRoot())) {
            return null;
        }
        try {
            Path downloadRoot = Paths.get(downloadConfig.getRootFolder()).toAbsolutePath().normalize();
            if (dir.equals(downloadRoot)) {
                return null;
            }
        } catch (InvalidPathException ignored) {
        }
        Path name = dir.getFileName();
        String expectedName = "novel-" + record.novelId();
        return name != null && expectedName.equals(name.toString()) ? dir : null;
    }

    private long archiveExpireSeconds() {
        return (long) multiModeConfig.getQuota().getArchiveExpireMinutes() * 60;
    }
}
