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
        String mode = setupService.getMode();
        if ("multi".equals(mode)) {
            String pdMode = multiModeConfig.getPostDownloadMode();
            if (("never-delete".equals(pdMode) || "timed-delete".equals(pdMode))
                    && novelDatabase.hasNovel(request.getNovelId())) {
                return ResponseEntity.ok(new AlreadyDownloadedResponse(
                        true, true, messages.get("download.already-downloaded")));
            }
        }

        boolean isAdmin = setupService.isAdminLoggedIn(httpRequest);
        stripUnauthorizedCollectionSelection(request, mode, isAdmin);

        String userUuid = null;
        if (!isAdmin && "multi".equals(mode) && multiModeConfig.getQuota().isEnabled()) {
            userUuid = UuidUtils.extractOrGenerateUuid(httpRequest);
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
    public ResponseEntity<NovelDownloadStatusResponse> getStatus(@PathVariable Long novelId) {
        NovelDownloadStatus status = novelDownloadService.getStatus(novelId);
        if (status == null) {
            return ResponseEntity.ok(new NovelDownloadStatusResponse(
                    false, messages.get("download.status.not-found"),
                    novelId, null, null, null, false, false, null, null, null));
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
                status.getBookmarkResult(),
                status.getCollectionResult()
        ));
    }

    private static String statusMessageCode(NovelDownloadStatus s) {
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
            top.sywyar.pixivdownload.download.DownloadActionResult bookmarkResult,
            top.sywyar.pixivdownload.download.DownloadActionResult collectionResult
    ) {}
}
