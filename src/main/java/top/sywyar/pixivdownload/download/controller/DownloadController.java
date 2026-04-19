package top.sywyar.pixivdownload.download.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.download.DownloadService;
import top.sywyar.pixivdownload.download.DownloadStatus;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.request.ArtworkBatchRequest;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.request.MoveArtworkRequest;
import top.sywyar.pixivdownload.download.response.*;
import top.sywyar.pixivdownload.quota.MultiModeConfig;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.SetupService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class DownloadController {

    private final DownloadService downloadService;
    private final SetupService setupService;
    private final UserQuotaService userQuotaService;
    private final MultiModeConfig multiModeConfig;
    private final PixivDatabase pixivDatabase;
    private final AuthorService authorService;

    @PostMapping("/download/pixiv")
    public ResponseEntity<?> downloadPixivImages(
            @Valid @RequestBody DownloadRequest request,
            HttpServletRequest httpRequest) {
        // SSRF 防护：同步校验所有下载 URL，非法 URL 抛出 SecurityException（由全局处理器返回 400）
        if (request.getOther().isUgoira() && request.getOther().getUgoiraZipUrl() != null) {
            DownloadService.validatePixivUrl(request.getOther().getUgoiraZipUrl());
        } else {
            for (String url : request.getImageUrls()) DownloadService.validatePixivUrl(url);
        }

        // 多人模式：never-delete/timed-delete 模式下，已下载过的作品直接返回成功，不消耗配额
        if ("multi".equals(setupService.getMode())) {
            String pdMode = multiModeConfig.getPostDownloadMode();
            if ("never-delete".equals(pdMode) || "timed-delete".equals(pdMode)) {
                if (pixivDatabase.hasArtwork(request.getArtworkId())) {
                    return ResponseEntity.ok(new AlreadyDownloadedResponse(true, true, "作品已下载，无需重复下载"));
                }
            }
        }

        String userUuid = null;
        boolean isAdmin = setupService.isAdminLoggedIn(httpRequest);

        // 多人模式且配额启用时，检查下载配额
        if (!isAdmin && "multi".equals(setupService.getMode()) && multiModeConfig.getQuota().isEnabled()) {
            userUuid = extractUserUuid(httpRequest);
            int imageCount = request.getOther().isUgoira() ? 1 : request.getImageUrls().size();
            UserQuotaService.QuotaCheckResult check = userQuotaService.checkAndReserve(userUuid, imageCount);

            if (!check.allowed()) {
                // 配额不足：触发打包，返回 429
                String archiveToken = userQuotaService.triggerArchive(userUuid);
                return ResponseEntity.status(429).body(new QuotaExceededResponse(
                        true,
                        "已达到下载限额，请下载已打包的文件后等待配额重置",
                        archiveToken,
                        (long) multiModeConfig.getQuota().getArchiveExpireMinutes() * 60,
                        check.artworksUsed(),
                        check.maxArtworks(),
                        check.resetSeconds()
                ));
            }
        }

        // 异步处理下载任务
        downloadService.downloadImages(
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
                .message("下载任务已开始处理")
                .downloadPath("正在下载到作品 " + request.getArtworkId() + " 文件夹")
                .downloadedCount(request.getImageUrls().size())
                .build());
    }

    /** 提取用户 UUID：优先 cookie，其次 X-User-UUID 请求头，最后基于 IP+UA 生成 */
    private String extractUserUuid(HttpServletRequest req) {
        return UuidUtils.extractOrGenerateUuid(req);
    }

    @GetMapping("/download/status")
    public ResponseEntity<DownloadResponse> getStatus() {
        return ResponseEntity.ok(DownloadResponse.builder().success(true).message("服务运行正常").build());
    }

    //获取作品下载状态
    @GetMapping("/download/status/{artworkId}")
    public ResponseEntity<DownloadStatusResponse> getDownloadStatus(@PathVariable Long artworkId) {
        DownloadStatus status = downloadService.getDownloadStatus(artworkId);
        if (status == null) {
            return ResponseEntity.ok(DownloadStatusResponse.builder()
                    .success(false).message("未找到该作品的下载状态").artworkId(artworkId).build());
        }

        log.info("artworkId: {},totalImages: {},downloadedCount: {}", artworkId, status.getTotalImages(), status.getDownloadedCount());
        return ResponseEntity.ok(DownloadStatusResponse.builder()
                .success(true)
                .message(status.getStatusDescription())
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
                .build());
    }

    @GetMapping("download/status/active")
    public ResponseEntity<ActiveDownloadResponse> getActiveDownload() {
        return ResponseEntity.ok(new ActiveDownloadResponse(downloadService.getDownloadStatus()));
    }

    //取消下载
    @PostMapping("/cancel/{artworkId}")
    public ResponseEntity<DownloadResponse> cancelDownload(@PathVariable Long artworkId) {
        downloadService.cancelDownload(artworkId);
        return ResponseEntity.ok(DownloadResponse.builder().success(true).message("下载任务已取消").build());
    }

    @GetMapping("/downloaded/{artworkId}")
    public ResponseEntity<DownloadedResponse> getDownloaded(
            @PathVariable Long artworkId,
            @RequestParam(defaultValue = "false") boolean verifyFiles) {
        DownloadedResponse downloadedResponse = getArtWorkDownloadedResponse(artworkId, verifyFiles);
        if (downloadedResponse == null) {
            return ResponseEntity.status(400).build();
        }
        return ResponseEntity.ok(downloadedResponse);
    }

    @PostMapping("/downloaded/batch")
    public ResponseEntity<BatchArtworksResponse> getBatchArtworks(@RequestBody ArtworkBatchRequest request) {
        List<ArtworkRecord> artworks = new LinkedList<>();
        for (Long artworkId : request.getArtworkIds()) {
            ArtworkRecord artwork = downloadService.getDownloadedRecord(artworkId);
            if (artwork == null) {
                continue;
            }
            artworks.add(artwork);
        }
        Map<Long, String> authorNames = resolveAuthorNames(artworks);
        List<DownloadedResponse> downloadedResponses = artworks.stream()
                .map(artwork -> toDownloadedResponse(artwork, authorNames))
                .toList();
        return ResponseEntity.ok(new BatchArtworksResponse(downloadedResponses));
    }

    @GetMapping("/downloaded/statistics")
    public ResponseEntity<StatisticsResponse> getStatistics() {
        return ResponseEntity.ok(downloadService.getStatistics());
    }

    @GetMapping("/downloaded/by-move-folder")
    public ResponseEntity<ArtworkIdResponse> getArtworkByMoveFolder(@RequestParam String path) {
        ArtworkRecord artwork = pixivDatabase.getArtworkByMoveFolder(path);
        if (artwork == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new ArtworkIdResponse(artwork.artworkId()));
    }

    @PostMapping("/downloaded/move/{artworkId}")
    public ResponseEntity<DownloadResponse> moveArtWork(
            @PathVariable Long artworkId,
            @Valid @RequestBody MoveArtworkRequest request) {
        String movePath = request.getMovePath().replaceAll("[/\\\\]+$", "");
        downloadService.moveArtWork(artworkId, movePath, request.getMoveTime());
        return ResponseEntity.ok(DownloadResponse.builder().success(true).message("已尝试记录移动操作").build());
    }

    @GetMapping("/downloaded/history")
    public ResponseEntity<HistoryResponse> getHistory() {
        return ResponseEntity.ok(new HistoryResponse(downloadService.getDownloadedRecord()));
    }

    @GetMapping("/downloaded/history/paged")
    public ResponseEntity<PagedHistoryResponse> getPagedHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "time") String sort) {
        long totalElements = downloadService.getArtworkCount();
        List<Long> artWorkIds = "author_id".equals(sort)
                ? downloadService.getSortAuthorArtworkPaged(page, size)
                : downloadService.getSortTimeArtworkPaged(page, size);
        List<ArtworkRecord> artworks = new LinkedList<>();

        for (Long artworkId : artWorkIds) {
            ArtworkRecord artwork = downloadService.getDownloadedRecord(artworkId);
            if (artwork != null) {
                artworks.add(artwork);
            }
        }

        Map<Long, String> authorNames = resolveAuthorNames(artworks);
        List<DownloadedResponse> downloadedResponses = artworks.stream()
                .map(artwork -> toDownloadedResponse(artwork, authorNames))
                .toList();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        return ResponseEntity.ok(new PagedHistoryResponse(downloadedResponses, totalElements, page, size, totalPages));
    }

    @GetMapping("/downloaded/thumbnail/{artworkId}/{page}")
    public ResponseEntity<ImageResponse> getThumbnail(
            @PathVariable Long artworkId,
            @PathVariable int page) throws IOException {
        ImageResponse image = downloadService.getImageResponse(artworkId, page, true);
        return image != null ? ResponseEntity.ok(image) : ResponseEntity.status(404).build();
    }

    @GetMapping("/downloaded/rawfile/{artworkId}/{page}")
    public ResponseEntity<byte[]> getRawFile(
            @PathVariable Long artworkId,
            @PathVariable int page) throws IOException {
        File file = downloadService.getImageFile(artworkId, page);
        if (file == null) return ResponseEntity.notFound().build();

        byte[] bytes = Files.readAllBytes(file.toPath());
        String name = file.getName().toLowerCase();
        MediaType mediaType;
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            mediaType = MediaType.IMAGE_JPEG;
        } else if (name.endsWith(".gif")) {
            mediaType = MediaType.IMAGE_GIF;
        } else if (name.endsWith(".webp")) {
            mediaType = MediaType.parseMediaType("image/webp");
        } else {
            mediaType = MediaType.IMAGE_PNG;
        }

        return ResponseEntity.ok().contentType(mediaType).body(bytes);
    }

    @GetMapping("/downloaded/image/{artworkId}/{page}")
    public ResponseEntity<ImageResponse> getImage(
            @PathVariable Long artworkId,
            @PathVariable int page) throws IOException {
        ImageResponse image = downloadService.getImageResponse(artworkId, page, false);
        return image != null ? ResponseEntity.ok(image) : ResponseEntity.status(404).build();
    }

    public DownloadedResponse getArtWorkDownloadedResponse(Long artworkId) {
        return getArtWorkDownloadedResponse(artworkId, false);
    }

    public DownloadedResponse getArtWorkDownloadedResponse(Long artworkId, boolean verifyFiles) {
        ArtworkRecord artwork = downloadService.getDownloadedRecord(artworkId, verifyFiles);
        if (artwork == null) {
            return null;
        }
        return toDownloadedResponse(artwork, resolveAuthorNames(List.of(artwork)));
    }

    private DownloadedResponse toDownloadedResponse(ArtworkRecord artwork, Map<Long, String> authorNames) {
        return DownloadedResponse.builder()
                .artworkId(artwork.artworkId())
                .title(artwork.title())
                .folder(artwork.folder())
                .count(artwork.count())
                .extensions(artwork.extensions())
                .time(artwork.time())
                .moved(artwork.moved())
                .moveFolder(artwork.moveFolder())
                .moveTime(artwork.moveTime())
                .isR18(artwork.isR18())
                .authorId(artwork.authorId())
                .authorName(artwork.authorId() == null ? null : authorNames.get(artwork.authorId()))
                .description(artwork.description())
                .tags(artwork.tags())
                .build();
    }

    private Map<Long, String> resolveAuthorNames(Collection<ArtworkRecord> artworks) {
        Set<Long> authorIds = new HashSet<>();
        for (ArtworkRecord artwork : artworks) {
            if (artwork != null && artwork.authorId() != null) {
                authorIds.add(artwork.authorId());
            }
        }
        return authorService.getAuthorNames(authorIds);
    }
}
