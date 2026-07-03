package top.sywyar.pixivdownload.download.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.download.ArtworkDownloadExecutor;
import top.sywyar.pixivdownload.download.DownloadStatus;
import top.sywyar.pixivdownload.download.response.ActiveDownloadResponse;
import top.sywyar.pixivdownload.core.download.response.DownloadResponse;
import top.sywyar.pixivdownload.download.response.DownloadStatusResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.setup.SetupService;

import java.util.List;

@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class DownloadStatusController {

    private final ArtworkDownloadExecutor artworkDownloadExecutor;
    private final SetupService setupService;
    private final AppMessages messages;

    @GetMapping("/download/status")
    public ResponseEntity<DownloadResponse> getStatus() {
        return ResponseEntity.ok(DownloadResponse.builder()
                .success(true)
                .message(messages.get("download.service.healthy"))
                .build());
    }

    //获取作品下载状态
    @GetMapping("/download/status/{artworkId}")
    public ResponseEntity<DownloadStatusResponse> getDownloadStatus(@PathVariable Long artworkId,
                                                                    HttpServletRequest httpRequest) {
        boolean adminScope = setupService.hasAdminScope(httpRequest);
        DownloadStatus status = adminScope
                ? artworkDownloadExecutor.getDownloadStatus(artworkId)
                : artworkDownloadExecutor.getDownloadStatus(artworkId, extractUserUuid(httpRequest), false);
        if (status == null) {
            return ResponseEntity.ok(DownloadStatusResponse.builder()
                    .success(false)
                    .message(messages.get("download.status.not-found"))
                    .artworkId(artworkId)
                    .build());
        }

        log.info("artworkId: {},totalImages: {},downloadedCount: {}", artworkId, status.getTotalImages(), status.getDownloadedCount());
        return ResponseEntity.ok(DownloadStatusResponse.builder()
                .success(true)
                .message(messages.get(status.getStatusMessageCode(), status.getStatusMessageArgs()))
                .artworkId(artworkId)
                .title(status.getTitle())
                .totalImages(status.getTotalImages())
                .downloadedCount(status.getDownloadedCount())
                .currentImageIndex(status.getCurrentImageIndex())
                .completed(status.isCompleted())
                .failed(status.isFailed())
                .cancelled(status.isCancelled())
                .progressPercentage(status.getProgressPercentage())
                .downloadPath(status.getDownloadPath())
                .bookmarkResult(status.getBookmarkResult())
                .collectionResult(status.getCollectionResult())
                .ugoiraProgress(status.getUgoiraProgress())
                .imageProgress(status.getImageProgress())
                .build());
    }

    @GetMapping("download/status/active")
    public ResponseEntity<ActiveDownloadResponse> getActiveDownload(HttpServletRequest httpRequest) {
        boolean adminScope = setupService.hasAdminScope(httpRequest);
        List<Long> active = adminScope
                ? artworkDownloadExecutor.getDownloadStatus()
                : artworkDownloadExecutor.getDownloadStatus(extractUserUuid(httpRequest), false);
        return ResponseEntity.ok(new ActiveDownloadResponse(active));
    }

    /** 提取用户 UUID：优先 cookie，其次 X-User-UUID 请求头，最后基于 IP+UA 生成 */
    private String extractUserUuid(HttpServletRequest req) {
        return UuidUtils.extractOrGenerateUuid(req);
    }
}
