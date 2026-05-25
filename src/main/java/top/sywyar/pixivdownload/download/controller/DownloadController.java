package top.sywyar.pixivdownload.download.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.download.DownloadService;
import top.sywyar.pixivdownload.download.DownloadStatus;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.download.request.ArtworkBatchRequest;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.request.MoveArtworkRequest;
import top.sywyar.pixivdownload.download.request.RecoverMetadataRequest;
import top.sywyar.pixivdownload.download.response.*;
import top.sywyar.pixivdownload.gallery.GalleryQuery;
import top.sywyar.pixivdownload.gallery.GalleryRepository;
import top.sywyar.pixivdownload.gallery.GuestRestriction;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.NovelDownloadService;
import top.sywyar.pixivdownload.quota.MultiModeConfig;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
    private final GuestAccessGuard guestAccessGuard;
    private final GalleryRepository galleryRepository;
    private final AppMessages messages;
    private final NovelDownloadService novelDownloadService;

    @PostMapping("/download/pixiv")
    public ResponseEntity<?> downloadPixivImages(
            @Valid @RequestBody DownloadRequest request,
            HttpServletRequest httpRequest) {
        String mode = setupService.getMode();
        if (request.getOther() == null) {
            request.setOther(new DownloadRequest.Other());
        }
        DownloadService.validateUserDownloadFolder(request.getOther());
        // SSRF 防护：同步校验所有下载 URL，非法 URL 抛出 LocalizedException（由全局处理器返回 400）
        if (request.getOther().isUgoira() && request.getOther().getUgoiraZipUrl() != null) {
            DownloadService.validatePixivUrl(request.getOther().getUgoiraZipUrl());
        } else {
            for (String url : request.getImageUrls()) DownloadService.validatePixivUrl(url);
        }

        // 多人模式：never-delete/timed-delete 模式下，已下载过的作品直接返回成功，不消耗配额
        if ("multi".equals(mode)) {
            String pdMode = multiModeConfig.getPostDownloadMode();
            if ("never-delete".equals(pdMode) || "timed-delete".equals(pdMode)) {
                if (pixivDatabase.hasArtwork(request.getArtworkId())) {
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
                ? downloadService.getDownloadStatus(artworkId)
                : downloadService.getDownloadStatus(artworkId, extractUserUuid(httpRequest), false);
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
                ? downloadService.getDownloadStatus()
                : downloadService.getDownloadStatus(extractUserUuid(httpRequest), false);
        return ResponseEntity.ok(new ActiveDownloadResponse(active));
    }

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

    @GetMapping("/downloaded/{artworkId}")
    public ResponseEntity<DownloadedResponse> getDownloaded(
            @PathVariable Long artworkId,
            @RequestParam(defaultValue = "false") boolean verifyFiles,
            HttpServletRequest httpRequest) {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        DownloadedResponse downloadedResponse = getArtWorkDownloadedResponse(artworkId, verifyFiles);
        if (downloadedResponse == null) {
            return ResponseEntity.status(400).build();
        }
        return ResponseEntity.ok(downloadedResponse);
    }

    /**
     * pixiv-batch 两阶段恢复：当 GET verifyFiles 命中裸记录恢复或确认磁盘有匹配文件时，
     * 前端拉到 Pixiv 元数据后 POST 到这里把缺字段补齐 / 写完整记录。
     * 幂等：DB 已有完整记录（title 非空）直接返回原记录，不覆盖。
     */
    @PostMapping("/downloaded/{artworkId}/recover-metadata")
    public ResponseEntity<DownloadedResponse> recoverMetadata(
            @PathVariable Long artworkId,
            @RequestBody(required = false) RecoverMetadataRequest body,
            HttpServletRequest httpRequest) {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        ArtworkRecord artwork = downloadService.recoverMetadata(artworkId, body);
        if (artwork == null) {
            return ResponseEntity.status(400).build();
        }
        return ResponseEntity.ok(toDownloadedResponse(artwork, resolveAuthorNames(List.of(artwork))));
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
    public ResponseEntity<StatisticsResponse> getStatistics(HttpServletRequest httpRequest) {
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        if (session != null) {
            return ResponseEntity.ok(getGuestStatistics(session));
        }
        return ResponseEntity.ok(downloadService.getStatistics());
    }

    @GetMapping("/downloaded/by-move-folder")
    public ResponseEntity<ArtworkIdResponse> getArtworkByMoveFolder(@RequestParam String path,
                                                                    HttpServletRequest httpRequest) {
        ArtworkRecord artwork = pixivDatabase.getArtworkByMoveFolder(path);
        if (artwork == null) {
            return ResponseEntity.notFound().build();
        }
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        if (session != null && !guestAccessGuard.isVisibleToGuest(artwork.artworkId(), session)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new ArtworkIdResponse(artwork.artworkId()));
    }

    @PostMapping("/downloaded/move/{artworkId}")
    public ResponseEntity<DownloadResponse> moveArtWork(
            @PathVariable Long artworkId,
            @Valid @RequestBody MoveArtworkRequest request) {
        String movePath = request.getMovePath().replaceAll("[/\\\\]+$", "");
        String presetRoot = request.getClassifierTargetFolder();
        if (presetRoot != null) {
            presetRoot = presetRoot.replaceAll("[/\\\\]+$", "");
            if (presetRoot.isEmpty()) presetRoot = null;
        }
        downloadService.moveArtWork(artworkId, movePath, request.getMoveTime(), presetRoot);
        return ResponseEntity.ok(DownloadResponse.builder()
                .success(true)
                .message(messages.get("download.move.record-attempted"))
                .build());
    }

    @GetMapping("/downloaded/history")
    public ResponseEntity<HistoryResponse> getHistory(HttpServletRequest httpRequest) {
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        if (session == null) {
            return ResponseEntity.ok(new HistoryResponse(downloadService.getDownloadedRecord()));
        }
        return ResponseEntity.ok(new HistoryResponse(getVisibleArtworkIds(session).stream()
                .map(String::valueOf)
                .toList()));
    }

    @GetMapping("/downloaded/history/paged")
    public ResponseEntity<PagedHistoryResponse> getPagedHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "time") String sort,
            HttpServletRequest httpRequest) {
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        if (session != null) {
            return ResponseEntity.ok(getGuestPagedHistory(page, size, sort, session));
        }
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
            @PathVariable int page,
            HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        ImageResponse image = downloadService.getImageResponse(artworkId, page, true);
        return image != null ? ResponseEntity.ok(image) : ResponseEntity.status(404).build();
    }

    @GetMapping("/downloaded/thumbnail-file/{artworkId}/{page}")
    public ResponseEntity<Resource> getThumbnailFile(
            @PathVariable Long artworkId,
            @PathVariable int page,
            HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        DownloadService.ThumbnailFile thumbnail = downloadService.getThumbnailFile(artworkId, page);
        if (thumbnail == null || !Files.isRegularFile(thumbnail.path())) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(thumbnail.path());
        return ResponseEntity.ok()
                .contentType(mediaTypeForImageExtension(thumbnail.extension()))
                .contentLength(Files.size(thumbnail.path()))
                .lastModified(Files.getLastModifiedTime(thumbnail.path()).toMillis())
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePrivate())
                .body(resource);
    }

    @GetMapping("/downloaded/rawfile/{artworkId}/{page}")
    public ResponseEntity<byte[]> getRawFile(
            @PathVariable Long artworkId,
            @PathVariable int page,
            HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
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
            @PathVariable int page,
            HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        ImageResponse image = downloadService.getImageResponse(artworkId, page, false);
        return image != null ? ResponseEntity.ok(image) : ResponseEntity.status(404).build();
    }

    private MediaType mediaTypeForImageExtension(String extension) {
        String normalized = extension == null ? "" : extension.toLowerCase();
        return switch (normalized) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            default -> MediaType.IMAGE_PNG;
        };
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

    private StatisticsResponse getGuestStatistics(GuestInviteSession session) {
        GalleryRepository.GuestStatistics stats = galleryRepository.findGuestStatistics(
                GuestRestriction.from(session));
        return new StatisticsResponse(true, stats.artworks(), stats.images(), stats.moved(),
                messages.get("download.statistics.success"));
    }

    private PagedHistoryResponse getGuestPagedHistory(int page, int size, String sort,
                                                      GuestInviteSession session) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        GalleryQuery query = GalleryQuery.builder()
                .page(safePage)
                .size(safeSize)
                .sort("author_id".equals(sort) ? "authorId" : "date")
                .order("desc")
                .r18("any")
                .ai("any")
                .guestRestriction(GuestRestriction.from(session))
                .build();
        GalleryRepository.QueryResult result = galleryRepository.findArtworkIds(query);
        List<ArtworkRecord> artworks = new LinkedList<>();
        for (Long artworkId : result.ids()) {
            ArtworkRecord artwork = downloadService.getDownloadedRecord(artworkId);
            if (artwork != null) {
                artworks.add(artwork);
            }
        }
        Map<Long, String> authorNames = resolveAuthorNames(artworks);
        List<DownloadedResponse> responses = artworks.stream()
                .map(artwork -> toDownloadedResponse(artwork, authorNames))
                .toList();
        long total = result.totalElements();
        int totalPages = (int) Math.ceil((double) total / safeSize);
        return new PagedHistoryResponse(responses, total, safePage, safeSize, totalPages);
    }

    private List<Long> getVisibleArtworkIds(GuestInviteSession session) {
        GalleryQuery query = GalleryQuery.builder()
                .page(0)
                .size(Integer.MAX_VALUE)
                .sort("date")
                .order("desc")
                .r18("any")
                .ai("any")
                .guestRestriction(GuestRestriction.from(session))
                .build();
        return galleryRepository.findArtworkIds(query).ids();
    }

    private DownloadedResponse toDownloadedResponse(ArtworkRecord artwork, Map<Long, String> authorNames) {
        List<TagDto> tags = pixivDatabase.getArtworkTags(artwork.artworkId());
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
                .xRestrict(artwork.xRestrict())
                .isAi(artwork.isAi())
                .authorId(artwork.authorId())
                .authorName(artwork.authorId() == null ? null : authorNames.get(artwork.authorId()))
                .description(artwork.description())
                .fileName(artwork.fileName())
                .fileNameTemplate(pixivDatabase.getFileNameTemplate(resolveFileNameId(artwork)))
                .tags(tags)
                .build();
    }

    private long resolveFileNameId(ArtworkRecord artwork) {
        return artwork.fileName() == null ? 1L : artwork.fileName();
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
