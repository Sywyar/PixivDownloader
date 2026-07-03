package top.sywyar.pixivdownload.download.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.download.ArtworkDownloadExecutor;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.response.AlreadyDownloadedResponse;
import top.sywyar.pixivdownload.core.download.response.DownloadResponse;
import top.sywyar.pixivdownload.download.response.QuotaExceededResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.SetupService;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DownloadTaskController {

    private final ArtworkDownloadExecutor artworkDownloadExecutor;
    private final SetupService setupService;
    private final UserQuotaService userQuotaService;
    private final MultiModeConfig multiModeConfig;
    private final PixivDatabase pixivDatabase;
    private final AppMessages messages;

    @PostMapping("/download/pixiv")
    public ResponseEntity<?> downloadPixivImages(
            @Valid @RequestBody DownloadRequest request,
            HttpServletRequest httpRequest) {
        String mode = setupService.getMode();
        if (request.getOther() == null) {
            request.setOther(new DownloadRequest.Other());
        }
        ArtworkDownloadExecutor.validateUserDownloadFolder(request.getOther());
        // SSRF 防护：同步校验所有下载 URL，非法 URL 抛出 LocalizedException（由全局处理器返回 400）
        if (request.getOther().isUgoira() && request.getOther().getUgoiraZipUrl() != null) {
            ArtworkDownloadExecutor.validatePixivUrl(request.getOther().getUgoiraZipUrl());
        } else {
            for (String url : request.getImageUrls()) ArtworkDownloadExecutor.validatePixivUrl(url);
        }

        // 多人模式：never-delete/timed-delete 模式下，已下载过的作品直接返回成功，不消耗配额。
        // 软删除的作品文件已不在磁盘，视为未下载放行（是否真正重下由客户端的下载设置决定）。
        if ("multi".equals(mode)) {
            String pdMode = multiModeConfig.getPostDownloadMode();
            if ("never-delete".equals(pdMode) || "timed-delete".equals(pdMode)) {
                if (pixivDatabase.hasActiveArtwork(request.getArtworkId())) {
                    return ResponseEntity.ok(new AlreadyDownloadedResponse(
                            true, true, messages.get("download.already-downloaded")));
                }
            }
        }

        String userUuid = null;
        boolean isAdmin = setupService.isAdminLoggedIn(httpRequest);
        stripUnauthorizedCollectionSelection(request, mode, isAdmin);
        if (!isAdmin && "multi".equals(mode)) {
            userUuid = extractUserUuid(httpRequest);
        }

        // 多人模式且配额启用时，检查下载配额
        if (userUuid != null && multiModeConfig.getQuota().isEnabled()) {
            int imageCount = request.getOther().isUgoira() ? 1 : request.getImageUrls().size();
            UserQuotaService.QuotaCheckResult check = userQuotaService.checkAndReserve(userUuid, imageCount);

            if (!check.allowed()) {
                // 配额不足：触发打包，返回 429
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

    /** 提取用户 UUID：优先 cookie，其次 X-User-UUID 请求头，最后基于 IP+UA 生成 */
    private String extractUserUuid(HttpServletRequest req) {
        return UuidUtils.extractOrGenerateUuid(req);
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
}
