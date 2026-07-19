package top.sywyar.pixivdownload.novel.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.config.MultiModeSettings;
import top.sywyar.pixivdownload.core.work.WorkActionResult;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaReservation;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaService;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.response.NovelAlreadyDownloadedResponse;
import top.sywyar.pixivdownload.novel.response.NovelDownloadResponse;
import top.sywyar.pixivdownload.novel.response.NovelQuotaExceededResponse;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.download.NovelDownloadStatus;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.novel.translation.NovelTranslationService;
import top.sywyar.pixivdownload.novelgallery.NovelGalleryService;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * 小说下载端点，归小说插件自有前缀 {@code /api/novel/**}（下载执行 / 状态 / 译文状态）。
 * <p>
 * 由 {@link PluginManagedBean} 排除出根包扫描、经 {@code NovelPluginConfiguration} 显式装配，
 * 随小说插件启停（禁用时本控制器不注册、{@code /api/novel/**} 一并 404）；旧址 {@code /api/download/**}
 * 下的小说路径由 {@code NovelDownloadLegacyForwardController} 服务端 forward 至此（懒迁移垫片，同随小说启停）。
 * 访问归属由 {@code NovelPlugin.routes()} 以 {@code AccessPolicy.VISITOR} 声明（multi 游客可下载、走配额；
 * solo 需会话；邀请访客 403），与插画下载 {@code /api/download/pixiv} 对称。
 */
@RestController
@RequestMapping("/api")
@PluginManagedBean
@Slf4j
@RequiredArgsConstructor
public class NovelDownloadController {

    private final NovelDownloadService novelDownloadService;
    private final NovelAutoTranslateService novelAutoTranslateService;
    private final NovelDatabase novelDatabase;
    private final NovelGalleryService novelGalleryService;
    private final NovelMergeService novelMergeService;
    private final NovelTranslationService novelTranslationService;
    private final ApplicationModeProvider applicationModeProvider;
    private final RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    private final WorkVisibilityService workVisibilityService;
    private final VisitorDownloadQuotaService visitorDownloadQuotaService;
    private final MultiModeSettings multiModeSettings;
    private final MessageResolver messages;

    @PostMapping("/novel/download")
    public ResponseEntity<?> downloadNovel(
            @Valid @RequestBody NovelDownloadRequest request,
            HttpServletRequest httpRequest) {
        if (request.getNovelId() == null || request.getNovelId() <= 0) {
            return ResponseEntity.badRequest().body(NovelDownloadResponse.builder()
                    .success(false)
                    .message(messages.get("pixiv.proxy.novel.id.invalid", String.valueOf(request.getNovelId())))
                    .build());
        }
        if (request.getOther() == null) {
            request.setOther(new NovelDownloadRequest.Other());
        }
        novelDownloadService.validateUserDownloadFolder(request.getOther());
        String mode = applicationModeProvider.getMode();
        if ("multi".equals(mode)) {
            String pdMode = multiModeSettings.getPostDownloadMode();
            // 软删除的小说文件已不在磁盘，视为未下载放行（是否真正重下由客户端的下载设置决定）
            if (("never-delete".equals(pdMode) || "timed-delete".equals(pdMode))
                    && novelDatabase.hasActiveNovel(request.getNovelId())) {
                return ResponseEntity.ok(new NovelAlreadyDownloadedResponse(
                        true, true, messages.get("download.already-downloaded")));
            }
        }

        RequestOwnerIdentity ownerIdentity = requestOwnerIdentityResolver.resolve(httpRequest);
        boolean isAdmin = requestOwnerIdentityResolver.isAdminAuthenticated(httpRequest);
        stripUnauthorizedCollectionSelection(request, mode, isAdmin);
        stripUnauthorizedAutoTranslate(request, mode, isAdmin);

        String userUuid = null;
        if (!isAdmin && "multi".equals(mode)) {
            userUuid = ownerIdentity.ownerUuid();
        }
        if (userUuid != null && multiModeSettings.isQuotaEnabled()) {
            VisitorDownloadQuotaReservation check = visitorDownloadQuotaService.checkAndReserve(userUuid, 1);
            if (!check.allowed()) {
                String archiveToken = visitorDownloadQuotaService.createArchive(userUuid);
                return ResponseEntity.status(429).body(new NovelQuotaExceededResponse(
                        true,
                        messages.get("download.quota.exceeded"),
                        archiveToken,
                        (long) multiModeSettings.getArchiveExpireMinutes() * 60,
                        check.quotaUnitsUsed(),
                        check.maxQuotaUnits(),
                        check.resetSeconds()
                ));
            }
        }

        novelDownloadService.download(request, userUuid);

        return ResponseEntity.ok(NovelDownloadResponse.builder()
                .success(true)
                .message(messages.get("download.task.started"))
                .downloadPath(messages.get("download.download-path.pending",
                        String.valueOf(request.getNovelId())))
                .downloadedCount(1)
                .build());
    }

    @GetMapping("/novel/status/{novelId}")
    public ResponseEntity<NovelDownloadStatusResponse> getStatus(@PathVariable Long novelId,
                                                                 HttpServletRequest httpRequest) {
        RequestOwnerIdentity ownerIdentity = requestOwnerIdentityResolver.resolve(httpRequest);
        NovelDownloadStatus status = ownerIdentity.admin()
                ? novelDownloadService.getStatus(novelId)
                : novelDownloadService.getStatus(novelId, ownerIdentity.ownerUuid(), false);
        if (status == null) {
            return ResponseEntity.ok(new NovelDownloadStatusResponse(
                    false, messages.get("download.status.not-found"),
                    novelId, null, null, null, false, false, null, 0, 0, 0L, 0L, null, null));
        }
        return ResponseEntity.ok(new NovelDownloadStatusResponse(
                true,
                messages.get(statusMessageCode(status), status.isFailed() && status.getErrorMessage() != null
                        ? new Object[]{status.getErrorMessage()} : new Object[0]),
                novelId,
                status.getTitle(),
                status.getFormat(),
                status.getStage(),
                status.isCompleted(),
                status.isFailed(),
                status.getDownloadPath(),
                status.getEmbeddedTotal(),
                status.getEmbeddedDone(),
                status.getCoverTotalBytes(),
                status.getCoverDownloadedBytes(),
                status.getBookmarkResult(),
                status.getCollectionResult()
        ));
    }

    /**
     * 「下载即自动翻译」状态轮询（admin-only：solo，或 multi 管理员）。翻译跑在服务端独立队列、生命周期独立于下载，
     * 前端下载完成后据此渲染「AI 翻译中 (Ns)」「等待前系列小说翻译完成，还有 n 个」。无该小说翻译记录时返回 204。
     */
    @GetMapping("/novel/translate-status/{novelId}")
    public ResponseEntity<NovelAutoTranslateService.StatusView> getTranslateStatus(
            @PathVariable Long novelId, HttpServletRequest httpRequest) {
        if (!requestOwnerIdentityResolver.resolve(httpRequest).admin()) {
            return ResponseEntity.status(403).build();
        }
        NovelAutoTranslateService.StatusView view = novelAutoTranslateService.getStatus(novelId);
        if (view == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(view);
    }

    /**
     * 下载工作台判重专用的三态查询：未下载 / 已下载 / 已下载但被画廊删除（软删除）。
     * 展示页也可通过 novel 插件的展示 API 读取同一事实；本端点服务下载队列的轻量状态查询。
     */
    @GetMapping("/novel/{novelId}/downloaded")
    public ResponseEntity<NovelDownloadedStateResponse> novelDownloadedState(@PathVariable long novelId) {
        var record = novelDatabase.getNovel(novelId);
        return ResponseEntity.ok(new NovelDownloadedStateResponse(
                record != null, record != null && record.deleted()));
    }

    @PostMapping("/novel/series/{seriesId}/merge")
    public ResponseEntity<MergeResponse> mergeSeries(
            @PathVariable long seriesId,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) String lang) throws IOException {
        NovelDownloadService.NovelFormat fmt = (format == null || format.isBlank())
                ? NovelDownloadService.NovelFormat.EPUB
                : NovelDownloadService.NovelFormat.parse(format);
        NovelMergeService.MergeResult result = (lang == null || lang.isBlank())
                ? novelMergeService.merge(seriesId, fmt)
                : novelMergeService.mergeVariant(seriesId, fmt, lang.trim());
        return ResponseEntity.ok(new MergeResponse(
                result.success(), result.message(),
                result.mergedPath(), result.chapterCount(), fmt.ext()));
    }

    /**
     * 浏览器下载系列合订本。每次都按当前数据库状态重新生成（原文基准 / 指定语言变体），
     * 然后把生成的文件作为附件回传。
     */
    @GetMapping("/novel/series/{seriesId}/merged")
    public ResponseEntity<byte[]> downloadMergedSeries(
            @PathVariable long seriesId,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) String lang,
            WorkVisibilityScope visibilityScope) throws IOException {
        Set<Long> filter = resolveGuestNovelSeriesFilter(visibilityScope);
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
        String encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        String asciiFallback = buildAsciiContentDispositionFallback(filename, seriesId, lang, fmt.ext());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mergedMimeFor(fmt)));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded);
        headers.setCacheControl(CacheControl.noStore());
        return ResponseEntity.ok().headers(headers).body(body);
    }

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

    @PostMapping("/novel/translate-lang-probe")
    public ResponseEntity<TranslateLangProbeResponse> translateLangProbe(
            @RequestBody TranslateLangProbeRequest request) {
        if (request == null || request.targetLanguage() == null || request.targetLanguage().isBlank()) {
            return ResponseEntity.ok(new TranslateLangProbeResponse("", false));
        }
        String code = novelTranslationService.resolveLangCode(request.targetLanguage());
        return ResponseEntity.ok(new TranslateLangProbeResponse(code, !code.isEmpty()));
    }

    @PostMapping("/novel/series/{seriesId}/translate-title")
    public ResponseEntity<TranslateSeriesTitleResponse> translateSeriesTitle(
            @PathVariable long seriesId, @RequestBody TranslateSeriesTitleRequest request) {
        if (request == null || request.targetLanguage() == null || request.targetLanguage().isBlank()) {
            return ResponseEntity.badRequest().body(new TranslateSeriesTitleResponse(
                    null, null, null, messages.get("novel.translate.missing-language")));
        }
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

    @GetMapping("/novel/series/{seriesId}/novel-ids")
    public ResponseEntity<NovelSeriesChapterIdsResponse> seriesNovelIds(@PathVariable long seriesId) {
        List<Long> ids = novelDatabase.getNovelsBySeriesId(seriesId).stream()
                .map(record -> record.novelId())
                .toList();
        return ResponseEntity.ok(new NovelSeriesChapterIdsResponse(ids));
    }

    private static String buildAsciiContentDispositionFallback(String filename, long seriesId,
                                                               String lang, String ext) {
        String sanitized = filename.replaceAll("[^\\x20-\\x7E]|[\"\\\\]", "_");
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

    private Set<Long> resolveGuestNovelSeriesFilter(WorkVisibilityScope visibilityScope) {
        WorkRestriction restriction = visibilityScope.restrictionFor(WorkType.NOVEL);
        if (restriction == null) return null;
        return novelGalleryService.visibleSeriesIds(restriction);
    }

    private static String mergedMimeFor(NovelDownloadService.NovelFormat fmt) {
        return switch (fmt) {
            case EPUB -> "application/epub+zip";
            case HTML -> "text/html; charset=UTF-8";
            case TXT -> "text/plain; charset=UTF-8";
        };
    }

    private static String statusMessageCode(NovelDownloadStatus s) {
        if (s.isCancelled()) return "download.status.cancelled";
        if (s.isFailed()) return "download.status.failed";
        if (s.isCompleted()) return "download.status.completed";
        return "download.status.in-progress";
    }

    private void stripUnauthorizedCollectionSelection(NovelDownloadRequest request, String mode, boolean isAdmin) {
        NovelDownloadRequest.Other other = request.getOther();
        if (other == null || other.getCollectionId() == null) return;
        if ("multi".equals(mode) && !isAdmin) {
            other.setCollectionId(null);
        }
    }

    /** 翻译为 admin-only：multi 模式普通游客的请求一律不触发自动翻译。 */
    private void stripUnauthorizedAutoTranslate(NovelDownloadRequest request, String mode, boolean isAdmin) {
        NovelDownloadRequest.Other other = request.getOther();
        if (other == null || !other.isAutoTranslate()) return;
        if ("multi".equals(mode) && !isAdmin) {
            other.setAutoTranslate(false);
        }
    }

    public record NovelDownloadStatusResponse(
            boolean success,
            String message,
            Long novelId,
            String title,
            String format,
            String stage,
            boolean completed,
            boolean failed,
            String downloadPath,
            int embeddedTotal,
            int embeddedDone,
            long coverTotalBytes,
            long coverDownloadedBytes,
            WorkActionResult bookmarkResult,
            WorkActionResult collectionResult
    ) {}

    public record NovelDownloadedStateResponse(boolean downloaded, boolean deleted) {}

    public record MergeResponse(boolean success, String message,
                                String mergedPath, int chapterCount, String format) {}

    public record TranslateRequest(String targetLanguage, Integer segmentSize,
                                   Boolean overwrite, String langHint, Long glossaryId,
                                   Boolean translateBody, Boolean translateTitle,
                                   Boolean translateDescription) {}

    public record TranslateResponse(String status, String langCode, String message, boolean truncated) {}

    public record TranslateLangProbeRequest(String targetLanguage) {}

    public record TranslateLangProbeResponse(String code, boolean valid) {}

    public record TranslateSeriesTitleRequest(String targetLanguage, String langHint, Long glossaryId,
                                              Boolean translateTitle, Boolean translateDescription) {}

    public record TranslateSeriesTitleResponse(String langCode, String title,
                                               String description, String message) {}

    public record NovelSeriesChapterIdsResponse(List<Long> novelIds) {}
}
