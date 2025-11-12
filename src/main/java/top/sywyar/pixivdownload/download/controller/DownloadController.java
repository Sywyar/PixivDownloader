package top.sywyar.pixivdownload.download.controller;

import com.sywyar.superjsonobject.SuperJsonObject;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.DownloadService;
import top.sywyar.pixivdownload.download.DownloadStatus;
import top.sywyar.pixivdownload.download.response.DownloadResponse;
import top.sywyar.pixivdownload.download.response.DownloadStatusResponse;
import top.sywyar.pixivdownload.download.response.DownloadedResponse;
import top.sywyar.pixivdownload.download.response.ThumbnailResponse;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // 允许跨域请求
@Slf4j
public class DownloadController {

    @Autowired
    private DownloadService downloadService;

    @PostMapping("download/pixiv")
    public ResponseEntity<DownloadResponse> downloadPixivImages(@Valid @RequestBody DownloadRequest request) {
        try {
            // 异步处理下载任务
            downloadService.downloadImages(
                    request.getArtworkId(),
                    request.getTitle(),
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

    @GetMapping("download/status")
    public ResponseEntity<DownloadResponse> getStatus() {
        return ResponseEntity.ok(new DownloadResponse(true, "服务运行正常"));
    }

    //获取作品下载状态
    @GetMapping("download/status/{artworkId}")
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

    @GetMapping("download/status/active")
    public ResponseEntity<List<Long>> getActiveDownload() {
        return ResponseEntity.ok(downloadService.getDownloadStatus());
    }

    //取消下载
    @PostMapping("/cancel/{artworkId}")
    public ResponseEntity<DownloadResponse> cancelDownload(@PathVariable Long artworkId) {
        downloadService.cancelDownload(artworkId);
        return ResponseEntity.ok(new DownloadResponse(true, "下载任务已取消"));
    }

    @GetMapping("/downloaded/{artworkId}")
    public ResponseEntity<DownloadedResponse> getDownloaded(@PathVariable Long artworkId) {
        SuperJsonObject artwork = downloadService.getDownloadedRecord(artworkId);
        if (artwork == null) {
            return ResponseEntity.ok(null);
        }
        boolean moved = artwork.getAsBoolean("moved");

        return ResponseEntity.ok(new DownloadedResponse.DownloadedResponseBuilder()
                .setArtworkId(artworkId)
                .setTitle(artwork.getAsString("title"))
                .setFolder(artwork.getAsString("folder"))
                .setCount(artwork.getAsInt("count"))
                .setTime(artwork.getAsLong("time"))
                .setMoved(moved)
                .setMoveFolder(moved ? artwork.getAsString("moveFolder") : null)
                .setMoveTime(moved ? artwork.getAsLong("moveTime") : null)
                .build());
    }

    @PostMapping("/downloaded/move/{artworkId}")
    public ResponseEntity<String> moveArtWork(
            @PathVariable Long artworkId,
            @RequestBody Map<String, Object> requestBody) {

        String movePath = (String) requestBody.get("movePath");
        Long moveTime = Long.valueOf(requestBody.get("moveTime").toString());

        downloadService.moveArtWork(artworkId, movePath, moveTime);

        return ResponseEntity.ok("已尝试记录移动操作");
    }

    @GetMapping("/downloaded/history")
    public ResponseEntity<List<String>> getHistory() {
        return ResponseEntity.ok(downloadService.getDownloadedRecord());
    }

    @GetMapping("/downloaded/thumbnail/{artworkId}/{page}")
    public ResponseEntity<ThumbnailResponse> getThumbnail(
            @PathVariable Long artworkId,
            @PathVariable int page) {
        ThumbnailResponse image;
        if ((image = downloadService.getThumbnail(artworkId, page)) != null) {
            return ResponseEntity.ok(image);
        }else {
            return ResponseEntity.status(404).build();
        }
    }
}