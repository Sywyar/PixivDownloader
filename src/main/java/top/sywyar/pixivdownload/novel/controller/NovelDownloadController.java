package top.sywyar.pixivdownload.novel.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.download.response.AlreadyDownloadedResponse;
import top.sywyar.pixivdownload.download.response.DownloadResponse;
import top.sywyar.pixivdownload.download.response.QuotaExceededResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.NovelAutoTranslateService;
import top.sywyar.pixivdownload.novel.NovelDownloadService;
import top.sywyar.pixivdownload.novel.NovelDownloadStatus;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.quota.MultiModeConfig;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.SetupService;

@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class NovelDownloadController {

    private final NovelDownloadService novelDownloadService;
    private final NovelAutoTranslateService novelAutoTranslateService;
    private final NovelDatabase novelDatabase;
    private final SetupService setupService;
    private final UserQuotaService userQuotaService;
    private final MultiModeConfig multiModeConfig;
    private final AppMessages messages;

    @PostMapping("/download/pixiv/novel")
    public ResponseEntity<?> downloadNovel(
            @Valid @RequestBody NovelDownloadRequest request,
            HttpServletRequest httpRequest) {
        if (request.getNovelId() == null || request.getNovelId() <= 0) {
            return ResponseEntity.badRequest().body(DownloadResponse.builder()
                    .success(false)
                    .message(messages.get("pixiv.proxy.novel.id.invalid", String.valueOf(request.getNovelId())))
                    .build());
        }
        if (request.getOther() == null) {
            request.setOther(new NovelDownloadRequest.Other());
        }
        NovelDownloadService.validateUserDownloadFolder(request.getOther());
        String mode = setupService.getMode();
        if ("multi".equals(mode)) {
            String pdMode = multiModeConfig.getPostDownloadMode();
            // 软删除的小说文件已不在磁盘，视为未下载放行（是否真正重下由客户端的下载设置决定）
            if (("never-delete".equals(pdMode) || "timed-delete".equals(pdMode))
                    && novelDatabase.hasActiveNovel(request.getNovelId())) {
                return ResponseEntity.ok(new AlreadyDownloadedResponse(
                        true, true, messages.get("download.already-downloaded")));
            }
        }

        boolean isAdmin = setupService.isAdminLoggedIn(httpRequest);
        stripUnauthorizedCollectionSelection(request, mode, isAdmin);
        stripUnauthorizedAutoTranslate(request, mode, isAdmin);

        String userUuid = null;
        if (!isAdmin && "multi".equals(mode)) {
            userUuid = UuidUtils.extractOrGenerateUuid(httpRequest);
        }
        if (userUuid != null && multiModeConfig.getQuota().isEnabled()) {
            UserQuotaService.QuotaCheckResult check = userQuotaService.checkAndReserve(userUuid, 1);
            if (!check.allowed()) {
                String archiveToken = userQuotaService.triggerArchive(userUuid);
                return ResponseEntity.status(429).body(new QuotaExceededResponse(
                        true,
                        messages.get("download.quota.exceeded"),
                        archiveToken,
                        (long) multiModeConfig.getQuota().getArchiveExpireMinutes() * 60,
                        check.artworksUsed(),
                        check.maxArtworks(),
                        check.resetSeconds()
                ));
            }
        }

        novelDownloadService.download(request, userUuid);

        return ResponseEntity.ok(DownloadResponse.builder()
                .success(true)
                .message(messages.get("download.task.started"))
                .downloadPath(messages.get("download.download-path.pending",
                        String.valueOf(request.getNovelId())))
                .downloadedCount(1)
                .build());
    }

    @GetMapping("/download/novel/status/{novelId}")
    public ResponseEntity<NovelDownloadStatusResponse> getStatus(@PathVariable Long novelId,
                                                                 HttpServletRequest httpRequest) {
        boolean adminScope = !"multi".equals(setupService.getMode()) || setupService.isAdminLoggedIn(httpRequest);
        NovelDownloadStatus status = adminScope
                ? novelDownloadService.getStatus(novelId)
                : novelDownloadService.getStatus(novelId, UuidUtils.extractOrGenerateUuid(httpRequest), false);
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
    @GetMapping("/download/novel/translate-status/{novelId}")
    public ResponseEntity<NovelAutoTranslateService.StatusView> getTranslateStatus(
            @PathVariable Long novelId, HttpServletRequest httpRequest) {
        boolean adminScope = !"multi".equals(setupService.getMode()) || setupService.isAdminLoggedIn(httpRequest);
        if (!adminScope) {
            return ResponseEntity.status(403).build();
        }
        NovelAutoTranslateService.StatusView view = novelAutoTranslateService.getStatus(novelId);
        if (view == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(view);
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
            top.sywyar.pixivdownload.download.DownloadActionResult bookmarkResult,
            top.sywyar.pixivdownload.download.DownloadActionResult collectionResult
    ) {}
}
