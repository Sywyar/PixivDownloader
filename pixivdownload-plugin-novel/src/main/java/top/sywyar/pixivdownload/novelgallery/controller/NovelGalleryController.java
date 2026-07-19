package top.sywyar.pixivdownload.novelgallery.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.novelgallery.NovelBatchService;
import top.sywyar.pixivdownload.quota.ArchiveExportSupport;
import top.sywyar.pixivdownload.novelgallery.NovelGalleryService;
import top.sywyar.pixivdownload.novel.NovelSeriesService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelDownloadedStatusRow;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.db.NovelSeries;
import top.sywyar.pixivdownload.novelgallery.NovelBatchRequest;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.core.work.query.SeriesNeighbors;
import top.sywyar.pixivdownload.core.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * {@code @RestController} 仅供 Spring MVC handler 检测；Bean 本身被
 * {@code @PluginManagedBean} 排除出根包扫描，由 {@code NovelPluginConfiguration} 提供。
 */
@PluginManagedBean
@RestController
@RequestMapping("/api/gallery")
@RequiredArgsConstructor
public class NovelGalleryController {

    private final NovelGalleryService novelGalleryService;
    private final NovelBatchService novelBatchService;
    private final NovelSeriesService novelSeriesService;
    private final NovelDatabase novelDatabase;
    private final WorkAssetService workAssetService;
    private final WorkVisibilityService workVisibilityService;

    @GetMapping("/novels")
    public NovelGalleryService.PagedNovels listNovels(
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "24") Integer size,
            @RequestParam(required = false, defaultValue = "date") String sort,
            @RequestParam(required = false, defaultValue = "desc") String order,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "all") String searchType,
            @RequestParam(required = false, defaultValue = "any") String r18,
            @RequestParam(required = false, defaultValue = "any") String ai,
            @RequestParam(required = false) String collectionIds,
            @RequestParam(required = false) String tagIds,
            @RequestParam(required = false) String notTagIds,
            @RequestParam(required = false) String orTagIds,
            @RequestParam(required = false) String authorIds,
            @RequestParam(required = false) String notAuthorIds,
            @RequestParam(required = false) String orAuthorIds,
            @RequestParam(required = false) Long authorId,
            @RequestParam(required = false) String seriesIds,
            @RequestParam(required = false) String notSeriesIds,
            @RequestParam(required = false) Long seriesId,
            WorkVisibilityScope visibilityScope) {
        java.util.Set<Long> mustAuthors = parseLongCsv(authorIds);
        if (authorId != null && authorId > 0) {
            if (mustAuthors == null) mustAuthors = new java.util.LinkedHashSet<>();
            mustAuthors.add(authorId);
        }
        java.util.Set<Long> mustSeries = parseLongCsv(seriesIds);
        if (seriesId != null && seriesId > 0) {
            if (mustSeries == null) mustSeries = new java.util.LinkedHashSet<>();
            mustSeries.add(seriesId);
        }
        return novelGalleryService.query(new NovelGalleryService.NovelGalleryQuery(
                Math.max(0, page), Math.max(1, Math.min(size, 200)),
                sort, order, search, NovelGalleryService.normalizeSearchType(searchType), r18, ai,
                parseLongCsv(collectionIds),
                parseLongCsv(tagIds), parseLongCsv(notTagIds), parseLongCsv(orTagIds),
                mustAuthors, parseLongCsv(notAuthorIds), parseLongCsv(orAuthorIds),
                mustSeries, parseLongCsv(notSeriesIds),
                visibilityScope.restrictionFor(WorkType.NOVEL)));
    }

    private static java.util.Set<Long> parseLongCsv(String csv) {
        if (csv == null || csv.isBlank()) return null;
        java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
        for (String s : csv.split(",")) {
            try { out.add(Long.parseLong(s.trim())); } catch (NumberFormatException ignored) {}
        }
        return out.isEmpty() ? null : out;
    }

    @GetMapping("/novels/authors")
    public NovelGalleryService.PagedAuthors listAuthors(
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "24") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "name") String sort,
            WorkVisibilityScope visibilityScope) {
        return novelGalleryService.getPagedAuthorsWithNovels(page, size, search, sort,
                visibilityScope.restrictionFor(WorkType.NOVEL));
    }

    @GetMapping("/novels/series")
    public NovelGalleryService.PagedSeries listNovelSeries(
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "24") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "title") String sort,
            WorkVisibilityScope visibilityScope) {
        return novelGalleryService.getPagedSeriesWithNovels(page, size, search, sort,
                visibilityScope.restrictionFor(WorkType.NOVEL));
    }

    @GetMapping("/novels/tags")
    public java.util.Map<String, Object> listNovelTags(
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "500") int limit,
            WorkVisibilityScope visibilityScope) {
        return java.util.Map.of("tags", novelGalleryService.listTags(search, limit,
                visibilityScope.restrictionFor(WorkType.NOVEL)));
    }

    @GetMapping("/novel/{novelId}")
    public ResponseEntity<NovelGalleryService.NovelView> findNovel(@PathVariable long novelId,
                                                                    WorkVisibilityScope visibilityScope) {
        workVisibilityService.requireVisible(visibilityScope, WorkType.NOVEL, novelId);
        NovelGalleryService.NovelView view = novelGalleryService.find(novelId);
        return view == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }

    /**
     * 删除单本小说（含磁盘文件与全部 DB 留存数据）。仅管理员可用：{@code /api/gallery/} 由
     * {@code AuthFilter} 按 monitor 语义保护，访客邀请白名单只放行 GET，DELETE 永远命中管理员校验。
     */
    @DeleteMapping("/novel/{novelId}")
    public ResponseEntity<DeleteResponse> deleteNovel(@PathVariable long novelId) {
        boolean deleted = novelGalleryService.deleteNovel(novelId);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new DeleteResponse(1));
    }

    /** 批量删除小说。POST 不在访客白名单内，仅管理员可用。 */
    @PostMapping("/novels/delete")
    public ResponseEntity<DeleteResponse> deleteNovels(@RequestBody NovelBatchRequest request) {
        int deleted = novelGalleryService.deleteNovels(novelBatchService.resolveNovelIds(request));
        return ResponseEntity.ok(new DeleteResponse(deleted));
    }

    @PostMapping("/novels/collect")
    public ResponseEntity<BatchCollectResponse> collectNovels(
            @RequestBody NovelBatchRequest request) {
        List<Long> ids = novelBatchService.resolveNovelIds(request);
        long collectionId = request == null || request.collectionId() == null ? 0L : request.collectionId();
        int changed = novelBatchService.collectNovels(ids, collectionId);
        return ResponseEntity.ok(new BatchCollectResponse(ids.size(), changed));
    }

    /** 批量导出小说。打包方式 / 格式 / 是否导出后删除由请求体决定。 */
    @PostMapping("/novels/export")
    public ResponseEntity<BatchExportResponse> exportNovels(
            @RequestBody NovelBatchRequest request) {
        ArchiveExportSupport.ExportResult result = novelBatchService.exportNovels(
                novelBatchService.resolveNovelIds(request),
                request == null ? null : request.groupBy(),
                request == null ? null : request.format(),
                request != null && Boolean.TRUE.equals(request.deleteAfter()));
        if (result.emptyArchive()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(new BatchExportResponse(
                result.archiveToken(), result.archiveExpireSeconds(), result.workCount(), result.fileCount()));
    }

    public record DeleteResponse(int deleted) {}

    public record BatchCollectResponse(int count, int changed) {}

    public record BatchExportResponse(String archiveToken, long archiveExpireSeconds, int count, int fileCount) {}

    @GetMapping("/novel/{novelId}/by-series")
    public ResponseEntity<List<NovelGalleryService.NovelView>> bySeries(
            @PathVariable long novelId,
            @RequestParam(defaultValue = "30") int limit,
            WorkVisibilityScope visibilityScope) {
        workVisibilityService.requireVisible(visibilityScope, WorkType.NOVEL, novelId);
        NovelGalleryService.NovelView current = novelGalleryService.find(novelId);
        if (current == null || current.seriesId() == null || current.seriesId() <= 0) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(filterForGuest(
                novelGalleryService.bySeries(current.seriesId(), limit),
                visibilityScope));
    }

    @GetMapping("/novel/{novelId}/series")
    public ResponseEntity<NovelSeriesNavResponse> seriesNav(
            @PathVariable long novelId,
            @RequestParam(required = false) String lang,
            WorkVisibilityScope visibilityScope) {
        workVisibilityService.requireVisible(visibilityScope, WorkType.NOVEL, novelId);
        SeriesNeighbors n = novelGalleryService.seriesNeighbors(novelId);
        if (n == null) {
            return ResponseEntity.ok(new NovelSeriesNavResponse(null, null, null, null, null));
        }
        // 选定了内容语言时优先用译后系列名 / 译后章节标题，缺失回退原文，与详情页主标题语义一致。
        String langCode = (lang == null || lang.isBlank()) ? null : lang.trim();
        String seriesTitle = n.seriesTitle();
        if (langCode != null) {
            String translatedSeries = novelDatabase.getSeriesTitleTranslation(n.seriesId(), langCode);
            if (translatedSeries != null && !translatedSeries.isBlank()) {
                seriesTitle = translatedSeries;
            }
        }
        return ResponseEntity.ok(new NovelSeriesNavResponse(
                n.seriesId(), seriesTitle, n.currentOrder(),
                visibleNeighbor(n.prev(), visibilityScope, langCode),
                visibleNeighbor(n.next(), visibilityScope, langCode)
        ));
    }

    private NeighborView visibleNeighbor(SeriesNeighbors.Neighbor neighbor,
                                         WorkVisibilityScope visibilityScope, String langCode) {
        if (neighbor == null) {
            return null;
        }
        if (!workVisibilityService.isVisible(visibilityScope, WorkType.NOVEL, neighbor.workId())) {
            return null;
        }
        String title = neighbor.title();
        if (langCode != null) {
            String translated = novelDatabase.getTranslationTitle(neighbor.workId(), langCode);
            if (translated != null && !translated.isBlank()) {
                title = translated;
            }
        }
        return new NeighborView(neighbor.workId(), title, neighbor.seriesOrder());
    }

    private List<NovelGalleryService.NovelView> filterForGuest(
            List<NovelGalleryService.NovelView> items,
            WorkVisibilityScope visibilityScope) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        return items.stream()
                .filter(item -> workVisibilityService.isVisible(
                        visibilityScope, WorkType.NOVEL, item.novelId()))
                .toList();
    }

    @GetMapping("/novel/{novelId}/cover")
    public ResponseEntity<byte[]> getNovelCover(@PathVariable long novelId,
                                                WorkVisibilityScope visibilityScope) throws IOException {
        workVisibilityService.requireVisible(visibilityScope, WorkType.NOVEL, novelId);
        WorkAssetFile cover = workAssetService.thumbnail(WorkType.NOVEL, novelId, 0).orElse(null);
        if (cover == null) {
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mimeFor(cover.extension())));
        headers.setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());
        return ResponseEntity.ok().headers(headers).body(Files.readAllBytes(cover.path()));
    }

    /**
     * 本地小说正文（原始 Pixiv markup，来自 {@code novels.raw_content}）。
     * 详情页据此在不访问 Pixiv 的前提下渲染正文；本地不存在该小说时 404。
     * 传入 {@code lang} 时返回该语言的 AI 译文（缺失则回退原文），供详情页内容语言切换使用。
     */
    @GetMapping("/novel/{novelId}/content")
    public ResponseEntity<NovelContentResponse> getNovelContent(
            @PathVariable long novelId,
            @RequestParam(required = false) String lang,
            WorkVisibilityScope visibilityScope) {
        workVisibilityService.requireVisible(visibilityScope, WorkType.NOVEL, novelId);
        NovelRecord rec = novelDatabase.getNovel(novelId);
        if (rec == null) {
            return ResponseEntity.notFound().build();
        }
        String original = rec.rawContent() == null ? "" : rec.rawContent();
        if (lang != null && !lang.isBlank()) {
            String langCode = lang.trim();
            String translated = novelDatabase.getTranslationContent(novelId, langCode);
            String translatedTitle = novelDatabase.getTranslationTitle(novelId, langCode);
            String translatedDescription = novelDatabase.getTranslationDescription(novelId, langCode);
            boolean hasBody = translated != null && !translated.isBlank();
            boolean hasTitle = translatedTitle != null && !translatedTitle.isBlank();
            boolean hasDescription = translatedDescription != null && !translatedDescription.isBlank();
            // 即便只译了标题 / 简介（正文为空），也要返回 translated=true 让前端展示译后元数据，
            // 正文则回退到原文 markup（与「未勾选正文翻译」语义一致）。
            if (hasBody || hasTitle || hasDescription) {
                return ResponseEntity.ok(new NovelContentResponse(
                        hasBody ? translated : original, langCode, true,
                        translatedTitle, translatedDescription));
            }
        }
        return ResponseEntity.ok(new NovelContentResponse(original, null, false, null, null));
    }

    /**
     * 给定一批小说 ID 与目标语言，批量返回各小说在该语言下已存在的译文标题。
     * 这是展示页读取能力；AI 翻译动作本身留在 novel-core 的 {@code /api/novel/**} 端点。
     */
    @GetMapping("/novel/translated-titles")
    public ResponseEntity<TranslatedTitlesResponse> translatedTitles(
            @RequestParam String lang,
            @RequestParam(required = false) String ids,
            WorkVisibilityScope visibilityScope) {
        if (lang == null || lang.isBlank() || ids == null || ids.isBlank()) {
            return ResponseEntity.ok(new TranslatedTitlesResponse(Map.of()));
        }
        Map<Long, String> out = new java.util.LinkedHashMap<>();
        String langCode = lang.trim();
        for (String token : ids.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            long novelId;
            try { novelId = Long.parseLong(t); } catch (NumberFormatException ignored) { continue; }
            if (!workVisibilityService.isVisible(visibilityScope, WorkType.NOVEL, novelId)) continue;
            String translated = novelDatabase.getTranslationTitle(novelId, langCode);
            if (translated != null && !translated.isBlank()) {
                out.put(novelId, translated);
            }
        }
        return ResponseEntity.ok(new TranslatedTitlesResponse(out));
    }

    /**
     * 内嵌图片字节流，路径: {novelFolder}/embed_{imageId}.{ext}。
     * imageId 来自 [uploadedimage:id] 占位符；不存在时 404。
     */
    @GetMapping("/novel/{novelId}/embed/{imageId}")
    public ResponseEntity<byte[]> getNovelEmbeddedImage(@PathVariable long novelId,
                                                        @PathVariable String imageId,
                                                        WorkVisibilityScope visibilityScope) throws IOException {
        workVisibilityService.requireVisible(visibilityScope, WorkType.NOVEL, novelId);
        if (imageId == null || imageId.isBlank() || !imageId.matches("[0-9A-Za-z_-]{1,40}")) {
            return ResponseEntity.notFound().build();
        }
        NovelRecord rec = novelDatabase.getNovel(novelId);
        if (rec == null) return ResponseEntity.notFound().build();
        String ext = novelDatabase.getNovelImageExt(novelId, imageId);
        if (ext == null || ext.isBlank()) return ResponseEntity.notFound().build();
        Path file = Paths.get(rec.folder(), "embed_" + imageId + "." + ext);
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mimeFor(ext)));
        headers.setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());
        return ResponseEntity.ok().headers(headers).body(Files.readAllBytes(file));
    }

    private static String mimeFor(String ext) {
        return switch (ext.toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "application/octet-stream";
        };
    }

    @GetMapping("/novel/series/{seriesId}")
    public ResponseEntity<NovelSeriesDetailResponse> getSeries(@PathVariable long seriesId,
                                                               @RequestParam(required = false) String lang,
                                                               WorkVisibilityScope visibilityScope) {
        Set<Long> filter = resolveGuestNovelSeriesFilter(visibilityScope);
        if (filter != null && !filter.contains(seriesId)) {
            return ResponseEntity.notFound().build();
        }
        NovelSeries series = novelDatabase.getSeries(seriesId);
        if (series == null) {
            return ResponseEntity.notFound().build();
        }
        String langCode = (lang == null || lang.isBlank()) ? null : lang.trim();
        String translatedTitle = langCode == null
                ? null
                : novelDatabase.getSeriesTitleTranslation(seriesId, langCode);
        String translatedDescription = langCode == null
                ? null
                : novelDatabase.getSeriesDescriptionTranslation(seriesId, langCode);
        return ResponseEntity.ok(toDetailResponse(series, translatedTitle, translatedDescription));
    }

    @GetMapping("/novel/series/{seriesId}/cover")
    public ResponseEntity<byte[]> getSeriesCover(@PathVariable long seriesId,
                                                 WorkVisibilityScope visibilityScope) throws IOException {
        Set<Long> filter = resolveGuestNovelSeriesFilter(visibilityScope);
        if (filter != null && !filter.contains(seriesId)) {
            return ResponseEntity.notFound().build();
        }
        NovelSeries series = novelDatabase.getSeries(seriesId);
        if (series == null || series.coverExt() == null || series.coverExt().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        // cover_folder 写入时已 toAbsolutePath().normalize()；缺省回退到当前 rootFolder 下的标准目录。
        Path folder = (series.coverFolder() != null && !series.coverFolder().isBlank())
                ? Path.of(series.coverFolder())
                : novelSeriesService.resolveCoverDir(seriesId);
        Path file = folder.resolve("cover." + series.coverExt());
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mimeFor(series.coverExt())));
        headers.setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());
        return ResponseEntity.ok().headers(headers).body(Files.readAllBytes(file));
    }

    private Set<Long> resolveGuestNovelSeriesFilter(WorkVisibilityScope visibilityScope) {
        WorkRestriction restriction = visibilityScope.restrictionFor(WorkType.NOVEL);
        if (restriction == null) return null;
        return novelGalleryService.visibleSeriesIds(restriction);
    }

    @PostMapping("/novels/downloaded-batch")
    public ResponseEntity<NovelDownloadedBatchResponse> downloadedBatch(
            @RequestBody NovelDownloadedBatchRequest request,
            WorkVisibilityScope visibilityScope) {
        List<Long> ids = request == null || request.novelIds() == null ? List.of() : request.novelIds();
        boolean includeDeleted = request != null
                && request.includeDeleted()
                && visibilityScope.restrictionFor(WorkType.NOVEL) == null;
        List<Long> downloadedIds = new ArrayList<>();
        List<Long> deletedIds = new ArrayList<>();
        for (NovelDownloadedStatusRow status : novelDatabase.getDownloadedStatuses(ids)) {
            if (status.deleted()) {
                if (includeDeleted) deletedIds.add(status.novelId());
            } else {
                downloadedIds.add(status.novelId());
            }
        }
        return ResponseEntity.ok(new NovelDownloadedBatchResponse(downloadedIds, deletedIds));
    }

    public record NovelDownloadedBatchRequest(List<Long> novelIds, boolean includeDeleted) {
        public NovelDownloadedBatchRequest(List<Long> novelIds) {
            this(novelIds, false);
        }
    }

    public record NovelDownloadedBatchResponse(List<Long> novelIds, List<Long> deletedNovelIds) {
        public NovelDownloadedBatchResponse(List<Long> novelIds) {
            this(novelIds, List.of());
        }
    }

    public record NovelContentResponse(String content, String lang, boolean translated,
                                       String translatedTitle, String translatedDescription) {}

    public record TranslatedTitlesResponse(Map<Long, String> titles) {}

    public record NovelSeriesNavResponse(Long seriesId, String seriesTitle, Long currentOrder,
                                         NeighborView prev, NeighborView next) {}

    public record NeighborView(long novelId, String title, long seriesOrder) {}

    public record NovelSeriesDetailResponse(long seriesId, String title, Long authorId, long updatedTime,
                                            String description, String coverExt, String coverFolder,
                                            List<TagDto> tags, List<String> translatedLanguages,
                                            String translatedTitle, String translatedDescription) {}

    private NovelSeriesDetailResponse toDetailResponse(NovelSeries series, String translatedTitle,
                                                       String translatedDescription) {
        return new NovelSeriesDetailResponse(
                series.seriesId(),
                series.title(),
                series.authorId(),
                series.updatedTime(),
                series.description(),
                series.coverExt(),
                series.coverFolder(),
                novelDatabase.getNovelSeriesTags(series.seriesId()),
                novelDatabase.getSeriesTranslatedLangs(series.seriesId()),
                translatedTitle,
                translatedDescription);
    }
}
