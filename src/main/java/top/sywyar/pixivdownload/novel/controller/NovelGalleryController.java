package top.sywyar.pixivdownload.novel.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.core.metadata.GuestRestriction;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.NovelBatchService;
import top.sywyar.pixivdownload.quota.ArchiveExportSupport;
import top.sywyar.pixivdownload.novel.NovelDownloadService;
import top.sywyar.pixivdownload.novel.NovelGalleryService;
import top.sywyar.pixivdownload.novel.NovelMergeService;
import top.sywyar.pixivdownload.novel.NovelSeriesService;
import top.sywyar.pixivdownload.novel.NovelTranslationService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.core.metadata.NovelGalleryRepository;
import top.sywyar.pixivdownload.core.metadata.NovelRecord;
import top.sywyar.pixivdownload.core.metadata.NovelSeries;
import top.sywyar.pixivdownload.core.metadata.NovelSeriesSummary;
import top.sywyar.pixivdownload.novel.request.NovelBatchRequest;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.work.query.SeriesNeighbors;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkAssetService;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
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
    private final NovelMergeService novelMergeService;
    private final NovelSeriesService novelSeriesService;
    private final NovelTranslationService novelTranslationService;
    private final NovelDatabase novelDatabase;
    private final NovelGalleryRepository novelGalleryRepository;
    private final WorkAssetService workAssetService;
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
     * 下载判重专用的三态查询：未下载 / 已下载 / 已下载但被画廊删除（软删除）。
     * 画廊详情对软删除返回 404，批量下载的「跳过已下载」需要据 {@code deleted} 区分
     * 「从未下载」与「已删除」，故单独提供本端点。
     */
    @GetMapping("/novel/{novelId}/downloaded")
    public ResponseEntity<NovelDownloadedStateResponse> novelDownloadedState(@PathVariable long novelId,
                                                                             HttpServletRequest httpRequest) {
        guestAccessGuard.requireNovelVisible(httpRequest, novelId);
        NovelRecord record = novelDatabase.getNovel(novelId);
        return ResponseEntity.ok(new NovelDownloadedStateResponse(
                record != null, record != null && record.deleted()));
    }

    public record NovelDownloadedStateResponse(boolean downloaded, boolean deleted) {}

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
            @RequestParam(required = false) String lang,
            HttpServletRequest httpRequest) {
        guestAccessGuard.requireNovelVisible(httpRequest, novelId);
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
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        return ResponseEntity.ok(new NovelSeriesNavResponse(
                n.seriesId(), seriesTitle, n.currentOrder(),
                visibleNeighbor(n.prev(), session, langCode),
                visibleNeighbor(n.next(), session, langCode)
        ));
    }

    private NeighborView visibleNeighbor(SeriesNeighbors.Neighbor neighbor,
                                         GuestInviteSession session, String langCode) {
        if (neighbor == null) {
            return null;
        }
        if (session != null && !guestAccessGuard.isNovelVisibleToGuest(neighbor.workId(), session)) {
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
            HttpServletRequest httpRequest) {
        guestAccessGuard.requireNovelVisible(httpRequest, novelId);
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
        // 翻译范围：调用方不传时默认全部为 true（保持向前兼容）；三者全为 false 时由 service 层拒绝。
        boolean translateBody = request.translateBody() == null || request.translateBody();
        boolean translateTitle = request.translateTitle() == null || request.translateTitle();
        boolean translateDescription = request.translateDescription() == null || request.translateDescription();
        if (!translateBody && !translateTitle && !translateDescription) {
            return ResponseEntity.badRequest().body(new TranslateResponse(
                    NovelTranslationService.Status.ERROR.name(), null,
                    messages.get("novel.translate.no-scope"), false));
        }
        NovelTranslationService.Result result = novelTranslationService.translateChapter(
                novelId, request.targetLanguage(), segmentSize, overwrite,
                request.langHint(), request.glossaryId(),
                translateBody, translateTitle, translateDescription);
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

    /**
     * 翻译某系列的系列名为指定目标语言（AI），并把结果落库。仅管理员可用（{@code /api/gallery/} 受 monitor 语义保护，
     * POST 不在访客白名单内）。前端「翻译整个系列」流程在章节正文翻译完成后调用一次，确保系列名也跟上变体语言。
     */
    @PostMapping("/novel/series/{seriesId}/translate-title")
    public ResponseEntity<TranslateSeriesTitleResponse> translateSeriesTitle(
            @PathVariable long seriesId, @RequestBody TranslateSeriesTitleRequest request) {
        if (request == null || request.targetLanguage() == null || request.targetLanguage().isBlank()) {
            return ResponseEntity.badRequest().body(new TranslateSeriesTitleResponse(
                    null, null, null, messages.get("novel.translate.missing-language")));
        }
        // 系列名 / 系列简介翻译范围：未传值默认 true；两者全 false 时拒绝。
        boolean translateTitle = request.translateTitle() == null || request.translateTitle();
        boolean translateDescription = request.translateDescription() == null || request.translateDescription();
        if (!translateTitle && !translateDescription) {
            return ResponseEntity.badRequest().body(new TranslateSeriesTitleResponse(
                    null, null, null, messages.get("novel.translate.no-scope")));
        }
        String langCode = novelTranslationService.translateSeriesTitle(
                seriesId, request.targetLanguage(), request.langHint(), request.glossaryId(),
                translateTitle, translateDescription);
        if (langCode == null || langCode.isBlank()) {
            return ResponseEntity.ok(new TranslateSeriesTitleResponse(null, null, null, null));
        }
        String translated = novelDatabase.getSeriesTitleTranslation(seriesId, langCode);
        String translatedDescription = novelDatabase.getSeriesDescriptionTranslation(seriesId, langCode);
        return ResponseEntity.ok(new TranslateSeriesTitleResponse(
                langCode, translated, translatedDescription, null));
    }

    /**
     * 给定一批小说 ID 与目标语言，批量返回各小说在该语言下已存在的译文标题（{@code novel_translations.title}）。
     * 不存在该语言译文或译文未带标题的小说不出现在返回 map 中。供章节卡片列表一次性替换为译后标题。
     */
    @GetMapping("/novel/translated-titles")
    public ResponseEntity<TranslatedTitlesResponse> translatedTitles(
            @RequestParam String lang,
            @RequestParam(required = false) String ids,
            HttpServletRequest httpRequest) {
        if (lang == null || lang.isBlank() || ids == null || ids.isBlank()) {
            return ResponseEntity.ok(new TranslatedTitlesResponse(Map.of()));
        }
        // 邀请访客需要按可见性裁剪：只回那些访客能看到的小说的译后标题，避免泄露隐藏作品标题。
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        Map<Long, String> out = new java.util.LinkedHashMap<>();
        String langCode = lang.trim();
        for (String token : ids.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            long novelId;
            try { novelId = Long.parseLong(t); } catch (NumberFormatException ignored) { continue; }
            if (session != null && !guestAccessGuard.isNovelVisibleToGuest(novelId, session)) continue;
            String translated = novelDatabase.getTranslationTitle(novelId, langCode);
            if (translated != null && !translated.isBlank()) {
                out.put(novelId, translated);
            }
        }
        return ResponseEntity.ok(new TranslatedTitlesResponse(out));
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
                                   Boolean overwrite, String langHint, Long glossaryId,
                                   Boolean translateBody, Boolean translateTitle,
                                   Boolean translateDescription) {}

    public record TranslateResponse(String status, String langCode, String message, boolean truncated) {}

    public record TranslateLangProbeRequest(String targetLanguage) {}

    public record TranslateLangProbeResponse(String code, boolean valid) {}

    public record TranslateSeriesTitleRequest(String targetLanguage, String langHint, Long glossaryId,
                                              Boolean translateTitle, Boolean translateDescription) {}

    /**
     * {@code langCode} 为空字符串或 {@code null} 表示翻译失败 / 标题为空；{@code title} 同步给出译后系列名；
     * {@code description} 同步给出译后系列简介（未翻译 / 失败时为 {@code null}）。
     */
    public record TranslateSeriesTitleResponse(String langCode, String title,
                                               String description, String message) {}

    public record TranslatedTitlesResponse(Map<Long, String> titles) {}

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
                                                               HttpServletRequest httpRequest) {
        Set<Long> filter = resolveGuestNovelSeriesFilter(httpRequest);
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

    /**
     * 浏览器下载系列合订本。每次都按当前数据库状态重新生成（原文基准 / 指定语言变体），
     * 然后把生成的文件作为附件回传。{@code lang} 为空 → 生成原文基准合订本；
     * 非空 → 仅生成该语言变体合订本，未翻译的章节回退到原文（与 {@link NovelMergeService} 既有语义一致）。
     */
    @GetMapping("/novel/series/{seriesId}/merged")
    public ResponseEntity<byte[]> downloadMergedSeries(
            @PathVariable long seriesId,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) String lang,
            HttpServletRequest httpRequest) throws IOException {
        Set<Long> filter = resolveGuestNovelSeriesFilter(httpRequest);
        if (filter != null && !filter.contains(seriesId)) {
            return ResponseEntity.notFound().build();
        }
        NovelDownloadService.NovelFormat fmt = (format == null || format.isBlank())
                ? NovelDownloadService.NovelFormat.EPUB
                : NovelDownloadService.NovelFormat.parse(format);
        NovelMergeService.MergeResult result = (lang == null || lang.isBlank())
                ? novelMergeService.merge(seriesId, fmt)
                : novelMergeService.mergeVariant(seriesId, fmt, lang.trim());
        if (!result.success() || result.mergedPath() == null) {
            return ResponseEntity.notFound().build();
        }
        Path file = Paths.get(result.mergedPath());
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        byte[] body = Files.readAllBytes(file);
        String filename = file.getFileName().toString();
        // RFC 5987 filename* 编码，避免中文系列名在 Content-Disposition 中被截断
        String encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        String asciiFallback = buildAsciiContentDispositionFallback(filename,
                seriesId, lang, fmt.ext());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mergedMimeFor(fmt)));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded);
        headers.setCacheControl(CacheControl.noStore());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /**
     * 构造 Content-Disposition 的 ASCII 兜底文件名。Tomcat 要求 {@code filename=} 参数只能用 ISO-8859-1 字符
     * 写入响应头；含中日韩等非 ASCII 字符（例如「【小说⑤卷】」「龙之王弟殿下」）会导致整条 header 被丢弃，
     * 下载随即失败。本方法把任何非 ASCII 可打印字符、控制字符、双引号、反斜杠都替换成 {@code _}；当替换后
     * 几乎只剩占位符时改用合成名 {@code series-{seriesId}[_lang].{ext}} 作为兜底，确保现代客户端走 {@code filename*=}
     * UTF-8 编码、旧客户端也能拿到一个合法可保存的回退名。
     */
    private static String buildAsciiContentDispositionFallback(String filename, long seriesId,
                                                               String lang, String ext) {
        String sanitized = filename.replaceAll("[^\\x20-\\x7E]|[\"\\\\]", "_");
        // 全是占位符的话连扩展名也丢了，直接用合成名兜底
        long printable = sanitized.chars()
                .filter(c -> c != '_' && c >= 0x20 && c <= 0x7E)
                .count();
        if (printable < 2) {
            return (lang == null || lang.isBlank())
                    ? "series-" + seriesId + "." + ext
                    : "series-" + seriesId + "_" + lang.trim().replaceAll("[^A-Za-z0-9-]", "_")
                            + "." + ext;
        }
        return sanitized;
    }

    private static String mergedMimeFor(NovelDownloadService.NovelFormat fmt) {
        return switch (fmt) {
            case EPUB -> "application/epub+zip";
            case HTML -> "text/html; charset=UTF-8";
            case TXT -> "text/plain; charset=UTF-8";
        };
    }

    @PostMapping("/novels/downloaded-batch")
    public ResponseEntity<NovelDownloadedBatchResponse> downloadedBatch(
            @RequestBody NovelDownloadedBatchRequest request) {
        List<Long> ids = request == null || request.novelIds() == null ? List.of() : request.novelIds();
        return ResponseEntity.ok(new NovelDownloadedBatchResponse(novelDatabase.getExistingNovelIds(ids)));
    }

    public record NovelDownloadedBatchRequest(List<Long> novelIds) {}

    public record NovelDownloadedBatchResponse(List<Long> novelIds) {}

    public record NovelContentResponse(String content, String lang, boolean translated,
                                       String translatedTitle, String translatedDescription) {}

    public record NovelSeriesNavResponse(Long seriesId, String seriesTitle, Long currentOrder,
                                         NeighborView prev, NeighborView next) {}

    public record NeighborView(long novelId, String title, long seriesOrder) {}

    public record MergeResponse(boolean success, String message,
                                String mergedPath, int chapterCount, String format) {}

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
