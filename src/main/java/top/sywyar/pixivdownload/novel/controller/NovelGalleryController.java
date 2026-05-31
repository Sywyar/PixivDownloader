package top.sywyar.pixivdownload.novel.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.download.ArtworkFileNameFormatter;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.gallery.GuestRestriction;
import top.sywyar.pixivdownload.novel.NovelDownloadService;
import top.sywyar.pixivdownload.novel.NovelGalleryService;
import top.sywyar.pixivdownload.novel.NovelMergeService;
import top.sywyar.pixivdownload.novel.NovelSeriesService;
import top.sywyar.pixivdownload.novel.NovelTranslationService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelGalleryRepository;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.db.NovelSeries;
import top.sywyar.pixivdownload.novel.db.NovelSeriesSummary;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/gallery")
@RequiredArgsConstructor
public class NovelGalleryController {

    private final NovelGalleryService novelGalleryService;
    private final NovelMergeService novelMergeService;
    private final NovelSeriesService novelSeriesService;
    private final NovelTranslationService novelTranslationService;
    private final NovelDatabase novelDatabase;
    private final NovelGalleryRepository novelGalleryRepository;
    private final PixivDatabase pixivDatabase;
    private final GuestAccessGuard guestAccessGuard;
    private final AppMessages messages;

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
            HttpServletRequest httpRequest) {
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
                GuestAccessGuard.extractSession(httpRequest)));
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
            HttpServletRequest httpRequest) {
        return novelGalleryService.getPagedAuthorsWithNovels(page, size, search, sort,
                GuestAccessGuard.extractSession(httpRequest));
    }

    @GetMapping("/novels/series")
    public NovelGalleryService.PagedSeries listNovelSeries(
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "24") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "title") String sort,
            HttpServletRequest httpRequest) {
        return novelGalleryService.getPagedSeriesWithNovels(page, size, search, sort,
                GuestAccessGuard.extractSession(httpRequest));
    }

    @GetMapping("/novels/tags")
    public java.util.Map<String, Object> listNovelTags(
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "500") int limit,
            HttpServletRequest httpRequest) {
        return java.util.Map.of("tags", novelGalleryService.listTags(search, limit,
                GuestAccessGuard.extractSession(httpRequest)));
    }

    @GetMapping("/novel/{novelId}")
    public ResponseEntity<NovelGalleryService.NovelView> findNovel(@PathVariable long novelId,
                                                                    HttpServletRequest httpRequest) {
        guestAccessGuard.requireNovelVisible(httpRequest, novelId);
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
    public ResponseEntity<DeleteResponse> deleteNovels(@RequestBody DeleteRequest request) {
        int deleted = novelGalleryService.deleteNovels(request == null ? null : request.ids());
        return ResponseEntity.ok(new DeleteResponse(deleted));
    }

    public record DeleteRequest(List<Long> ids) {}

    public record DeleteResponse(int deleted) {}

    @GetMapping("/novel/{novelId}/by-series")
    public ResponseEntity<List<NovelGalleryService.NovelView>> bySeries(
            @PathVariable long novelId,
            @RequestParam(defaultValue = "30") int limit,
            HttpServletRequest httpRequest) {
        guestAccessGuard.requireNovelVisible(httpRequest, novelId);
        NovelGalleryService.NovelView current = novelGalleryService.find(novelId);
        if (current == null || current.seriesId() == null || current.seriesId() <= 0) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(filterForGuest(
                novelGalleryService.bySeries(current.seriesId(), limit),
                GuestAccessGuard.extractSession(httpRequest)));
    }

    @GetMapping("/novel/{novelId}/series")
    public ResponseEntity<NovelSeriesNavResponse> seriesNav(
            @PathVariable long novelId,
            HttpServletRequest httpRequest) {
        guestAccessGuard.requireNovelVisible(httpRequest, novelId);
        NovelGalleryService.SeriesNeighbors n = novelGalleryService.seriesNeighbors(novelId);
        if (n == null) {
            return ResponseEntity.ok(new NovelSeriesNavResponse(null, null, null, null, null));
        }
        return ResponseEntity.ok(new NovelSeriesNavResponse(
                n.seriesId(), n.seriesTitle(), n.currentOrder(),
                visibleNeighbor(n.prev(), GuestAccessGuard.extractSession(httpRequest)),
                visibleNeighbor(n.next(), GuestAccessGuard.extractSession(httpRequest))
        ));
    }

    private NeighborView visibleNeighbor(NovelGalleryService.NeighborView neighbor,
                                         GuestInviteSession session) {
        if (neighbor == null) {
            return null;
        }
        if (session != null && !guestAccessGuard.isNovelVisibleToGuest(neighbor.novelId(), session)) {
            return null;
        }
        return new NeighborView(neighbor.novelId(), neighbor.title(), neighbor.seriesOrder());
    }

    private List<NovelGalleryService.NovelView> filterForGuest(
            List<NovelGalleryService.NovelView> items,
            GuestInviteSession session) {
        if (session == null || items == null || items.isEmpty()) {
            return items;
        }
        return items.stream()
                .filter(item -> guestAccessGuard.isNovelVisibleToGuest(item.novelId(), session))
                .toList();
    }

    @GetMapping("/novel/{novelId}/cover")
    public ResponseEntity<byte[]> getNovelCover(@PathVariable long novelId,
                                                HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireNovelVisible(httpRequest, novelId);
        NovelRecord rec = novelDatabase.getNovel(novelId);
        if (rec == null || rec.coverExt() == null || rec.coverExt().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        String baseName = resolveStoredNovelBaseName(rec);
        Path file = Paths.get(rec.folder(), baseName + "_thumb." + rec.coverExt());
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mimeFor(rec.coverExt())));
        headers.setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());
        return ResponseEntity.ok().headers(headers).body(Files.readAllBytes(file));
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
            HttpServletRequest httpRequest) {
        guestAccessGuard.requireNovelVisible(httpRequest, novelId);
        NovelRecord rec = novelDatabase.getNovel(novelId);
        if (rec == null) {
            return ResponseEntity.notFound().build();
        }
        String original = rec.rawContent() == null ? "" : rec.rawContent();
        if (lang != null && !lang.isBlank()) {
            String translated = novelDatabase.getTranslationContent(novelId, lang.trim());
            if (translated != null && !translated.isBlank()) {
                return ResponseEntity.ok(new NovelContentResponse(translated, lang.trim(), true));
            }
        }
        return ResponseEntity.ok(new NovelContentResponse(original, null, false));
    }

    /**
     * 翻译单本小说为目标语言（AI）。仅管理员可用：{@code /api/gallery/} 受 monitor 语义保护，
     * POST 不在访客白名单内。译文保存到本地（{@code novel_translations}），供后续直接渲染、不重复请求 AI。
     */
    @PostMapping("/novel/{novelId}/translate")
    public ResponseEntity<TranslateResponse> translateNovel(@PathVariable long novelId,
                                                            @RequestBody TranslateRequest request) {
        if (request == null || request.targetLanguage() == null || request.targetLanguage().isBlank()) {
            return ResponseEntity.badRequest().body(new TranslateResponse(
                    NovelTranslationService.Status.ERROR.name(), null,
                    messages.get("novel.translate.missing-language"), false));
        }
        int segmentSize = request.segmentSize() == null ? 0 : Math.max(0, request.segmentSize());
        boolean overwrite = Boolean.TRUE.equals(request.overwrite());
        NovelTranslationService.Result result = novelTranslationService.translateChapter(
                novelId, request.targetLanguage(), segmentSize, overwrite,
                request.langHint(), request.glossaryId());
        return ResponseEntity.ok(new TranslateResponse(
                result.status().name(), result.langCode(), result.message(), result.truncated()));
    }

    /**
     * 用户输入的自由文本目标语言（如「简体中文」/「english」）→ 规范 BCP-47 代码（{@code zh-CN} 等）。
     * 仅管理员可用。系列批量翻译前调用一次，让首章也能凭已有译文的 lang_code 直接走 DB 跳过，
     * 不必为识别语言再花一次完整翻译请求。
     */
    @PostMapping("/novel/translate-lang-probe")
    public ResponseEntity<TranslateLangProbeResponse> translateLangProbe(
            @RequestBody TranslateLangProbeRequest request) {
        if (request == null || request.targetLanguage() == null || request.targetLanguage().isBlank()) {
            return ResponseEntity.ok(new TranslateLangProbeResponse("", false));
        }
        String code = novelTranslationService.resolveLangCode(request.targetLanguage());
        return ResponseEntity.ok(new TranslateLangProbeResponse(code, !code.isEmpty()));
    }

    /** 系列内全部章节的 novel_id（按 series_order 升序），供「翻译整个系列」前端逐章循环。 */
    @GetMapping("/novel/series/{seriesId}/novel-ids")
    public ResponseEntity<NovelSeriesChapterIdsResponse> seriesNovelIds(@PathVariable long seriesId,
                                                                        HttpServletRequest httpRequest) {
        Set<Long> filter = resolveGuestNovelSeriesFilter(httpRequest);
        if (filter != null && !filter.contains(seriesId)) {
            return ResponseEntity.notFound().build();
        }
        List<Long> ids = novelDatabase.getNovelsBySeriesId(seriesId).stream()
                .map(NovelRecord::novelId)
                .toList();
        return ResponseEntity.ok(new NovelSeriesChapterIdsResponse(ids));
    }

    public record TranslateRequest(String targetLanguage, Integer segmentSize,
                                   Boolean overwrite, String langHint, Long glossaryId) {}

    public record TranslateResponse(String status, String langCode, String message, boolean truncated) {}

    public record TranslateLangProbeRequest(String targetLanguage) {}

    public record TranslateLangProbeResponse(String code, boolean valid) {}

    public record NovelSeriesChapterIdsResponse(List<Long> novelIds) {}

    /**
     * 内嵌图片字节流，路径: {novelFolder}/embed_{imageId}.{ext}。
     * imageId 来自 [uploadedimage:id] 占位符；不存在时 404。
     */
    @GetMapping("/novel/{novelId}/embed/{imageId}")
    public ResponseEntity<byte[]> getNovelEmbeddedImage(@PathVariable long novelId,
                                                        @PathVariable String imageId,
                                                        HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireNovelVisible(httpRequest, novelId);
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

    private String resolveStoredNovelBaseName(NovelRecord rec) {
        String template = rec.fileName() == null
                ? ArtworkFileNameFormatter.DEFAULT_TEMPLATE
                : pixivDatabase.getFileNameTemplate(rec.fileName());
        String authorName = rec.fileAuthorNameId() == null
                ? ""
                : pixivDatabase.getFileAuthorName(rec.fileAuthorNameId());
        if (authorName == null) authorName = "";
        List<String> names = ArtworkFileNameFormatter.formatAll(
                template, rec.novelId(), rec.title(), rec.authorId(), authorName,
                rec.time(), 1, rec.isAi(), rec.xRestrict());
        return names.isEmpty() ? String.valueOf(rec.novelId()) : names.get(0);
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
                                                               HttpServletRequest httpRequest) {
        Set<Long> filter = resolveGuestNovelSeriesFilter(httpRequest);
        if (filter != null && !filter.contains(seriesId)) {
            return ResponseEntity.notFound().build();
        }
        NovelSeries series = novelDatabase.getSeries(seriesId);
        if (series == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDetailResponse(series));
    }

    @GetMapping("/novel/series/{seriesId}/cover")
    public ResponseEntity<byte[]> getSeriesCover(@PathVariable long seriesId,
                                                 HttpServletRequest httpRequest) throws IOException {
        Set<Long> filter = resolveGuestNovelSeriesFilter(httpRequest);
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

    private Set<Long> resolveGuestNovelSeriesFilter(HttpServletRequest httpRequest) {
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        if (session == null) return null;
        Set<Long> ids = new HashSet<>();
        for (NovelSeriesSummary summary : novelGalleryRepository
                .findVisibleNovelSeriesCounts(GuestRestriction.forNovel(session))) {
            ids.add(summary.seriesId());
        }
        return ids;
    }

    @PostMapping("/novel/series/{seriesId}/merge")
    public ResponseEntity<MergeResponse> mergeSeries(
            @PathVariable long seriesId,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) String lang) throws IOException {
        // 合订本默认且推荐 EPUB（内嵌封面/插图、多级目录、系列元数据）；
        // 显式传入 txt/html 时按指定格式合订。
        NovelDownloadService.NovelFormat fmt = (format == null || format.isBlank())
                ? NovelDownloadService.NovelFormat.EPUB
                : NovelDownloadService.NovelFormat.parse(format);
        // lang 为空：生成原文基准合订本 + 重生全部语言变体；指定 lang：仅重生该语言变体合订本。
        NovelMergeService.MergeResult result = (lang == null || lang.isBlank())
                ? novelMergeService.merge(seriesId, fmt)
                : novelMergeService.mergeVariant(seriesId, fmt, lang.trim());
        return ResponseEntity.ok(new MergeResponse(
                result.success(), result.message(),
                result.mergedPath(), result.chapterCount(), fmt.ext()));
    }

    @PostMapping("/novels/downloaded-batch")
    public ResponseEntity<NovelDownloadedBatchResponse> downloadedBatch(
            @RequestBody NovelDownloadedBatchRequest request) {
        List<Long> ids = request == null || request.novelIds() == null ? List.of() : request.novelIds();
        return ResponseEntity.ok(new NovelDownloadedBatchResponse(novelDatabase.getExistingNovelIds(ids)));
    }

    public record NovelDownloadedBatchRequest(List<Long> novelIds) {}

    public record NovelDownloadedBatchResponse(List<Long> novelIds) {}

    public record NovelContentResponse(String content, String lang, boolean translated) {}

    public record NovelSeriesNavResponse(Long seriesId, String seriesTitle, Long currentOrder,
                                         NeighborView prev, NeighborView next) {}

    public record NeighborView(long novelId, String title, long seriesOrder) {}

    public record MergeResponse(boolean success, String message,
                                String mergedPath, int chapterCount, String format) {}

    public record NovelSeriesDetailResponse(long seriesId, String title, Long authorId, long updatedTime,
                                            String description, String coverExt, String coverFolder,
                                            List<TagDto> tags, List<String> translatedLanguages) {}

    private NovelSeriesDetailResponse toDetailResponse(NovelSeries series) {
        return new NovelSeriesDetailResponse(
                series.seriesId(),
                series.title(),
                series.authorId(),
                series.updatedTime(),
                series.description(),
                series.coverExt(),
                series.coverFolder(),
                novelDatabase.getNovelSeriesTags(series.seriesId()),
                novelDatabase.getSeriesTranslatedLangs(series.seriesId()));
    }
}
