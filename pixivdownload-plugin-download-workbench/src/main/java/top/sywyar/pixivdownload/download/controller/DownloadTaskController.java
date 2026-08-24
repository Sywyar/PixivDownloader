package top.sywyar.pixivdownload.download.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.config.MultiModeSettings;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;
import top.sywyar.pixivdownload.download.response.error.ErrorResponse;
import top.sywyar.pixivdownload.download.response.status.DownloadResponse;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaReservation;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaService;
import top.sywyar.pixivdownload.download.ArtworkDownloadExecutor;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.response.error.QuotaExceededResponse;
import top.sywyar.pixivdownload.download.response.status.AlreadyDownloadedResponse;
import top.sywyar.pixivdownload.download.web.LocalizedException;
import top.sywyar.pixivdownload.download.web.WorkbenchErrorResponses;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;

import java.util.Locale;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DownloadTaskController {

    private final ArtworkDownloadExecutor artworkDownloadExecutor;
    private final ApplicationModeProvider applicationModeProvider;
    private final RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    private final VisitorDownloadQuotaService visitorDownloadQuotaService;
    private final MultiModeSettings multiModeSettings;
    private final WorkQueryService workQueryService;
    private final MessageResolver messages;

    @PostMapping("/download/pixiv")
    public ResponseEntity<?> downloadPixivImages(
            @Valid @RequestBody DownloadRequest request,
            HttpServletRequest httpRequest) {
        String mode = applicationModeProvider.getMode();
        if (request.getOther() == null) {
            request.setOther(new DownloadRequest.Other());
        }
        artworkDownloadExecutor.validateUserDownloadFolder(request.getOther());
        // SSRF 防护：同步校验所有下载 URL，非法 URL 由本插件的异常投影返回 400。
        if (request.getOther().isUgoira() && request.getOther().getUgoiraZipUrl() != null) {
            ArtworkDownloadExecutor.validatePixivUrl(request.getOther().getUgoiraZipUrl());
        } else {
            for (String url : request.getImageUrls()) ArtworkDownloadExecutor.validatePixivUrl(url);
        }

        // 多人模式：never-delete/timed-delete 模式下，已下载过的作品直接返回成功，不消耗配额。
        // 软删除的作品文件已不在磁盘，视为未下载放行（是否真正重下由客户端的下载设置决定）。
        if ("multi".equals(mode)) {
            String pdMode = multiModeSettings.getPostDownloadMode();
            if ("never-delete".equals(pdMode) || "timed-delete".equals(pdMode)) {
                if (workQueryService.hasActiveWork(WorkType.ARTWORK, request.getArtworkId())) {
                    return ResponseEntity.ok(new AlreadyDownloadedResponse(
                            true, true, messages.get("download.already-downloaded")));
                }
            }
        }

        RequestOwnerIdentity identity = requestOwnerIdentityResolver.resolve(httpRequest);
        String userUuid = null;
        boolean isAdmin = identity.admin();
        stripUnauthorizedCollectionSelection(request, mode, isAdmin);
        if (!isAdmin && "multi".equals(mode)) {
            userUuid = identity.ownerUuid();
        }

        // 多人模式且配额启用时，检查下载配额
        if (userUuid != null && multiModeSettings.isQuotaEnabled()) {
            int imageCount = request.getOther().isUgoira() ? 1 : request.getImageUrls().size();
            VisitorDownloadQuotaReservation reservation =
                    visitorDownloadQuotaService.checkAndReserve(userUuid, imageCount);

            if (!reservation.allowed()) {
                // 配额不足：触发打包，返回 429
                String archiveToken = visitorDownloadQuotaService.createArchive(userUuid);
                return ResponseEntity.status(429).body(new QuotaExceededResponse(
                        true,
                        messages.get("download.quota.exceeded"),
                        archiveToken,
                        (long) multiModeSettings.getArchiveExpireMinutes() * 60,
                        reservation.quotaUnitsUsed(),
                        reservation.maxQuotaUnits(),
                        reservation.resetSeconds()
                ));
            }
        }

        // 异步处理下载任务
        artworkDownloadExecutor.downloadImages(
                request.getArtworkId(),
                request.getTitle(),
                request.getImageUrls(),
                request.getReferer(),
                request.getOther(),
                request.getCookie(),
                userUuid
        );

        return ResponseEntity.ok(DownloadResponse.builder()
                .success(true)
                .message(messages.get("download.task.started"))
                .downloadPath(messages.get("download.download-path.pending", String.valueOf(request.getArtworkId())))
                .downloadedCount(request.getImageUrls().size())
                .build());
    }

    private void stripUnauthorizedCollectionSelection(DownloadRequest request, String mode, boolean isAdmin) {
        DownloadRequest.Other other = request.getOther();
        if (other == null || other.getCollectionId() == null) {
            return;
        }
        if ("multi".equals(mode) && !isAdmin) {
            other.setCollectionId(null);
        }
    }

    @ExceptionHandler(LocalizedException.class)
    public ResponseEntity<ErrorResponse> handleLocalized(LocalizedException failure, Locale locale) {
        return WorkbenchErrorResponses.localized(failure, messages, locale);
    }
}
