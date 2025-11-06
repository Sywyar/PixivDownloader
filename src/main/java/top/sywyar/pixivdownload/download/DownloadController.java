package top.sywyar.pixivdownload.download;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/download")
@CrossOrigin(origins = "*") // 允许跨域请求
@Slf4j
public class DownloadController {

    @Autowired
    private DownloadService downloadService;

    @PostMapping("/pixiv")
    public ResponseEntity<DownloadResponse> downloadPixivImages(@Valid @RequestBody DownloadRequest request) {
        try {
            // 异步处理下载任务
            downloadService.downloadImages(
                    request.getArtworkId(),
                    request.getImageUrls(),
                    request.getReferer(),
                    request.getCookie()  // 传递Cookie
            );

            return ResponseEntity.ok(new DownloadResponse(
                    true,
                    "下载任务已开始处理",
                    "正在下载到作品 " + request.getArtworkId() + " 文件夹",
                    request.getImageUrls().size()
            ));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    new DownloadResponse(false, "下载请求处理失败: " + e.getMessage())
            );
        }
    }

    @GetMapping("/status")
    public ResponseEntity<DownloadResponse> getStatus() {
        return ResponseEntity.ok(new DownloadResponse(true, "服务运行正常"));
    }

    // 新增：获取作品下载状态
    @GetMapping("/status/{artworkId}")
    public ResponseEntity<DownloadStatusResponse> getDownloadStatus(@PathVariable Long artworkId) {
        DownloadStatus status = downloadService.getDownloadStatus(artworkId);
        if (status == null) {
            return ResponseEntity.ok(new DownloadStatusResponse(false, "未找到该作品的下载状态", artworkId));
        }

        log.info("artworkId: {},totalImages: {},downloadedCount: {}", artworkId, status.getTotalImages(), status.getDownloadedCount());
        return ResponseEntity.ok(new DownloadStatusResponse(
                true,
                status.getStatusDescription(),
                artworkId,
                status.getTotalImages(),
                status.getDownloadedCount(),
                status.getCurrentImageIndex(),
                status.isCompleted(),
                status.isFailed(),
                status.isCancelled(),
                status.getProgressPercentage(),
                status.getDownloadPath()
        ));
    }

    // 新增：取消下载
    @PostMapping("/cancel/{artworkId}")
    public ResponseEntity<DownloadResponse> cancelDownload(@PathVariable Long artworkId) {
        downloadService.cancelDownload(artworkId);
        return ResponseEntity.ok(new DownloadResponse(true, "下载任务已取消"));
    }
}