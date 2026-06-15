package top.sywyar.pixivdownload.download.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.download.DownloadService;
import top.sywyar.pixivdownload.download.response.DownloadResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.setup.SetupService;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DownloadQueueController {

    private final DownloadService downloadService;
    private final SetupService setupService;
    private final NovelDownloadService novelDownloadService;
    private final AppMessages messages;

    //取消下载
    @PostMapping({"/cancel/{artworkId}", "/download/cancel/{artworkId}"})
    public ResponseEntity<DownloadResponse> cancelDownload(@PathVariable Long artworkId,
                                                           HttpServletRequest httpRequest) {
        if (setupService.hasAdminScope(httpRequest)) {
            downloadService.cancelDownload(artworkId);
        } else {
            downloadService.cancelDownload(artworkId, extractUserUuid(httpRequest), false);
        }
        return ResponseEntity.ok(DownloadResponse.builder()
                .success(true)
                .message(messages.get("download.cancelled"))
                .build());
    }

    @PostMapping("/download/queue/clear")
    public ResponseEntity<DownloadResponse> clearDownloadQueue(HttpServletRequest httpRequest) {
        int cleared;
        // 多人模式下的访客只能强制清除自己（owner）的下载；solo 模式或多人模式下已登录的管理员清除全部，
        // 与 cancelDownload 的归属语义保持一致。
        if ("multi".equals(setupService.getMode()) && !setupService.isAdminLoggedIn(httpRequest)) {
            String ownerUuid = extractUserUuid(httpRequest);
            cleared = downloadService.forceClearDownloadsForOwner(ownerUuid);
            cleared += novelDownloadService.forceClearDownloadsForOwner(ownerUuid);
        } else {
            cleared = downloadService.forceClearDownloads();
            cleared += novelDownloadService.forceClearDownloads();
        }
        return ResponseEntity.ok(DownloadResponse.builder()
                .success(true)
                .message(messages.get("download.queue-cleared", String.valueOf(cleared)))
                .build());
    }

    /** 提取用户 UUID：优先 cookie，其次 X-User-UUID 请求头，最后基于 IP+UA 生成 */
    private String extractUserUuid(HttpServletRequest req) {
        return UuidUtils.extractOrGenerateUuid(req);
    }
}
