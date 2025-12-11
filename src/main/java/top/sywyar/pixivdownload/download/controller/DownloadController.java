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
import top.sywyar.pixivdownload.download.response.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // 允许跨域请求
@Slf4j
public class DownloadController {

    @Autowired
    private DownloadService downloadService;

    @PostMapping("/download/pixiv")
    public ResponseEntity<DownloadResponse> downloadPixivImages(@Valid @RequestBody DownloadRequest request) {
        try {
            // 异步处理下载任务
            downloadService.downloadImages(
                    request.getArtworkId(),
                    request.getTitle(),
                    request.getImageUrls(),
                    request.getReferer(),
                    request.getOther(),
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

    @GetMapping("/download/status")
    public ResponseEntity<DownloadResponse> getStatus() {
        return ResponseEntity.ok(new DownloadResponse(true, "服务运行正常"));
    }

    //获取作品下载状态
    @GetMapping("/download/status/{artworkId}")
    public ResponseEntity<DownloadStatusResponse> getDownloadStatus(@PathVariable Long artworkId) {
        DownloadStatus status = downloadService.getDownloadStatus(artworkId);
        if (status == null) {
            return ResponseEntity.ok(new DownloadStatusResponse(false, "未找到该作品的下载状态", artworkId, null));
        }

        log.info("artworkId: {},totalImages: {},downloadedCount: {}", artworkId, status.getTotalImages(), status.getDownloadedCount());
        return ResponseEntity.ok(new DownloadStatusResponse(
                true,
                status.getStatusDescription(),
                artworkId,
                status.getTitle(),
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
        DownloadedResponse downloadedResponse = getArtWorkDownloadedResponse(artworkId);
        if (downloadedResponse == null) {
            return ResponseEntity.status(400).build();
        }
        return ResponseEntity.ok(downloadedResponse);
    }

    @PostMapping("/downloaded/batch")
    public ResponseEntity<List<DownloadedResponse>> getBatchArtworks(@RequestBody List<Long> artworkIds) {
        List<DownloadedResponse> downloadedResponses = new LinkedList<>();
        for (Long artworkId : artworkIds) {
            SuperJsonObject artwork = downloadService.getDownloadedRecord(artworkId);
            if (artwork == null) {
                return ResponseEntity.ok(null);
            }
            boolean moved = artwork.getAsBoolean("moved");

            downloadedResponses.add(new DownloadedResponse.DownloadedResponseBuilder()
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
        return ResponseEntity.ok(downloadedResponses);
    }

    @GetMapping("/downloaded/statistics")
    public ResponseEntity<StatisticsResponse> getStatistics() {
        StatisticsResponse response = downloadService.getStatistics();
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
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

    @GetMapping("/downloaded/history/paged")
    public ResponseEntity<Map<String, Object>> getPagedHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            List<DownloadedResponse> downloadedResponses = new LinkedList<>();

            List<Long> artWorkIds = downloadService.getSortTimeArtwork();

            long totalElements = artWorkIds.size();

            for (int i = page * size; i < (page + 1) * size; i++) {
                if (i >= artWorkIds.size()) {
                    break;
                }
                DownloadedResponse response = getArtWorkDownloadedResponse(artWorkIds.get(i));
                if (response != null) {
                    downloadedResponses.add(response);
                }
            }

            // 返回包含分页元数据的Map
            Map<String, Object> response = new HashMap<>();
            response.put("content", downloadedResponses);
            response.put("totalElements", totalElements);
            response.put("page", page);
            response.put("size", size);
            response.put("totalPages", (int) Math.ceil((double) totalElements / size));

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取下载历史失败，第{}页，大小：{}，原因：{}", page, size, e.getMessage(), e);

            Map<String, Object> response = new HashMap<>();
            response.put("content", new LinkedList<>());
            response.put("totalElements", 0);
            response.put("page", 0);
            response.put("size", 0);
            response.put("totalPages", 0);

            return ResponseEntity.badRequest().body(response);

        }
    }

    @GetMapping("/downloaded/thumbnail/{artworkId}/{page}")
    public ResponseEntity<ImageResponse> getThumbnail(
            @PathVariable Long artworkId,
            @PathVariable int page) {
        ImageResponse image;
        if ((image = downloadService.getImageResponse(artworkId, page, true)) != null) {
            return ResponseEntity.ok(image);
        } else {
            return ResponseEntity.status(404).build();
        }
    }

    @GetMapping("/downloaded/image/{artworkId}/{page}")
    public ResponseEntity<ImageResponse> getImage(
            @PathVariable Long artworkId,
            @PathVariable int page) {
        ImageResponse image;
        if ((image = downloadService.getImageResponse(artworkId, page, false)) != null) {
            return ResponseEntity.ok(image);
        } else {
            return ResponseEntity.status(404).build();
        }
    }

    public DownloadedResponse getArtWorkDownloadedResponse(Long artworkId) {
        SuperJsonObject artwork = downloadService.getDownloadedRecord(artworkId);
        if (artwork == null) {
            return null;
        }
        boolean moved = artwork.has("moved") && artwork.getAsBoolean("moved");

        return new DownloadedResponse.DownloadedResponseBuilder()
                .setArtworkId(artworkId)
                .setTitle(artwork.getAsString("title"))
                .setFolder(artwork.getAsString("folder"))
                .setCount(artwork.getAsInt("count"))
                .setTime(artwork.getAsLong("time"))
                .setMoved(moved)
                .setMoveFolder(moved ? artwork.getAsString("moveFolder") : null)
                .setMoveTime(moved ? artwork.getAsLong("moveTime") : null)
                .build();
    }
}