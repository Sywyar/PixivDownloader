package top.sywyar.pixivdownload.gallery;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.download.response.DownloadedResponse;
import top.sywyar.pixivdownload.download.response.PagedHistoryResponse;
import top.sywyar.pixivdownload.plugin.api.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.SeriesNeighbors;
import top.sywyar.pixivdownload.quota.ArchiveExportSupport;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.util.*;

/**
 * {@code @RestController} 仅供 Spring MVC handler 检测；Bean 本身被
 * {@code @PluginManagedBean} 排除出根包扫描，由 {@link GalleryPluginConfiguration} 提供。
 */
@PluginManagedBean
@RestController
@RequestMapping("/api/gallery")
@RequiredArgsConstructor
public class GalleryController {

    private final GalleryService galleryService;
    private final GalleryBatchService galleryBatchService;
    private final GuestAccessGuard guestAccessGuard;

    @GetMapping("/artworks")
    public PagedHistoryResponse listArtworks(
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "24") Integer size,
            @RequestParam(required = false, defaultValue = "date") String sort,
            @RequestParam(required = false, defaultValue = "desc") String order,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "all") String searchType,
            @RequestParam(required = false, defaultValue = "any") String r18,
            @RequestParam(required = false, defaultValue = "any") String ai,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) String collectionIds,
            @RequestParam(required = false) String tagIds,
            @RequestParam(required = false) String notTagIds,
            @RequestParam(required = false) String orTagIds,
            @RequestParam(required = false) String authorIds,
            @RequestParam(required = false) String notAuthorIds,
            @RequestParam(required = false) String orAuthorIds,
            @RequestParam(required = false) Long authorId,
            @RequestParam(required = false) String seriesIds,
            @RequestParam(required = false) String notSeriesIds,
            @RequestParam(required = false) Long seriesId,
            HttpServletRequest httpRequest) {

        List<Long> requiredAuthorIds = parseLongList(authorIds);
        if (authorId != null && authorId > 0) {
            if (requiredAuthorIds == null) requiredAuthorIds = new ArrayList<>();
            if (!requiredAuthorIds.contains(authorId)) requiredAuthorIds.add(authorId);
        }
        List<Long> requiredSeriesIds = parseLongList(seriesIds);
        if (seriesId != null && seriesId > 0) {
            if (requiredSeriesIds == null) requiredSeriesIds = new ArrayList<>();
            if (!requiredSeriesIds.contains(seriesId)) requiredSeriesIds.add(seriesId);
        }
        GalleryQuery query = GalleryQuery.normalize(
                page, size, sort, order, search, r18, ai,
                parseFormats(format),
                parseLongList(collectionIds),
                parseLongList(tagIds),
                parseLongList(notTagIds),
                parseLongList(orTagIds),
                requiredAuthorIds,
                parseLongList(notAuthorIds),
                parseLongList(orAuthorIds),
                requiredSeriesIds,
                parseLongList(notSeriesIds));
        query.setSearchType(GalleryQuery.normalizeSearchType(searchType));
        query.setGuestRestriction(GuestRestriction.from(GuestAccessGuard.extractSession(httpRequest)));
        return galleryService.query(query);
    }

    @GetMapping("/tags")
    public Map<String, Object> listTags(
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "500") int limit,
            HttpServletRequest httpRequest) {
        GuestRestriction restriction = GuestRestriction.from(GuestAccessGuard.extractSession(httpRequest));
        List<GalleryRepository.TagOption> tags = galleryService.listTags(search, limit, restriction);
        return Map.of("tags", tags);
    }

    @GetMapping("/tags/lookup")
    public ResponseEntity<GalleryRepository.TagOption> findTag(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String translatedName) {
        GalleryRepository.TagOption tag = galleryService.findTag(name, translatedName);
        return tag == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(tag);
    }

    @GetMapping("/artwork/{artworkId}")
    public ResponseEntity<DownloadedResponse> artwork(@PathVariable long artworkId,
                                                      HttpServletRequest httpRequest) {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        DownloadedResponse resp = galleryService.findArtwork(artworkId);
        return resp == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(resp);
    }

    @GetMapping("/artwork/{artworkId}/related")
    public ResponseEntity<List<DownloadedResponse>> related(
            @PathVariable long artworkId,
            @RequestParam(defaultValue = "12") int limit,
            HttpServletRequest httpRequest) {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        List<DownloadedResponse> all = galleryService.related(artworkId, limit);
        return ResponseEntity.ok(filterForGuest(all, session));
    }

    @GetMapping("/artwork/{artworkId}/by-author")
    public ResponseEntity<List<DownloadedResponse>> byAuthor(
            @PathVariable long artworkId,
            @RequestParam(defaultValue = "12") int limit,
            HttpServletRequest httpRequest) {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        List<DownloadedResponse> all = galleryService.byAuthor(artworkId, limit);
        return ResponseEntity.ok(filterForGuest(all, session));
    }

    @GetMapping("/artwork/{artworkId}/by-series")
    public ResponseEntity<List<DownloadedResponse>> bySeries(
            @PathVariable long artworkId,
            @RequestParam(defaultValue = "30") int limit,
            HttpServletRequest httpRequest) {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        List<DownloadedResponse> all = galleryService.bySeries(artworkId, limit);
        return ResponseEntity.ok(filterForGuest(all, session));
    }

    @GetMapping("/artwork/{artworkId}/series")
    public ResponseEntity<SeriesNavResponse> seriesNav(
            @PathVariable long artworkId,
            HttpServletRequest httpRequest) {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        SeriesNeighbors neighbors = galleryService.seriesNeighbors(artworkId);
        if (neighbors == null) {
            return ResponseEntity.ok(new SeriesNavResponse(null, null, null, null, null));
        }
        SeriesNavResponse.NeighborView prev = neighbors.prev() == null
                ? null
                : (session != null && !guestAccessGuard.isVisibleToGuest(neighbors.prev().workId(), session)
                    ? null
                    : new SeriesNavResponse.NeighborView(
                        neighbors.prev().workId(),
                        neighbors.prev().title(),
                        neighbors.prev().seriesOrder()));
        SeriesNavResponse.NeighborView next = neighbors.next() == null
                ? null
                : (session != null && !guestAccessGuard.isVisibleToGuest(neighbors.next().workId(), session)
                    ? null
                    : new SeriesNavResponse.NeighborView(
                        neighbors.next().workId(),
                        neighbors.next().title(),
                        neighbors.next().seriesOrder()));
        return ResponseEntity.ok(new SeriesNavResponse(
                neighbors.seriesId(),
                neighbors.seriesTitle(),
                neighbors.currentOrder(),
                prev,
                next
        ));
    }

    public record SeriesNavResponse(Long seriesId, String seriesTitle, Long currentOrder,
                                    NeighborView prev, NeighborView next) {
        public record NeighborView(long artworkId, String title, long seriesOrder) {}
    }

    /**
     * 删除单个作品（含磁盘文件与全部 DB 留存数据）。仅管理员可用：{@code /api/gallery/} 由
     * {@code AuthFilter} 按 monitor 语义保护，访客邀请白名单只放行 GET，DELETE 永远命中管理员校验。
     */
    @DeleteMapping("/artwork/{artworkId}")
    public ResponseEntity<DeleteResponse> deleteArtwork(@PathVariable long artworkId) {
        boolean deleted = galleryService.deleteArtwork(artworkId);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new DeleteResponse(1));
    }

    /** 批量删除作品。POST 不在访客白名单内，仅管理员可用。 */
    @PostMapping("/artworks/delete")
    public ResponseEntity<DeleteResponse> deleteArtworks(@RequestBody ArtworkBatchRequest request) {
        int deleted = galleryService.deleteArtworks(galleryBatchService.resolveArtworkIds(request));
        return ResponseEntity.ok(new DeleteResponse(deleted));
    }

    @PostMapping("/artworks/collect")
    public ResponseEntity<BatchCollectResponse> collectArtworks(
            @RequestBody ArtworkBatchRequest request) {
        List<Long> ids = galleryBatchService.resolveArtworkIds(request);
        long collectionId = request == null || request.collectionId() == null ? 0L : request.collectionId();
        int changed = galleryBatchService.collectArtworks(ids, collectionId);
        return ResponseEntity.ok(new BatchCollectResponse(ids.size(), changed));
    }

    /** 批量导出作品。打包方式 / 格式 / 是否导出后删除由请求体决定。 */
    @PostMapping("/artworks/export")
    public ResponseEntity<BatchExportResponse> exportArtworks(
            @RequestBody ArtworkBatchRequest request) {
        ArchiveExportSupport.ExportResult result = galleryBatchService.exportArtworks(
                galleryBatchService.resolveArtworkIds(request),
                request == null ? null : request.groupBy(),
                request == null ? null : request.format(),
                request != null && Boolean.TRUE.equals(request.deleteAfter()));
        if (result.emptyArchive()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(new BatchExportResponse(
                result.archiveToken(), result.archiveExpireSeconds(), result.workCount(), result.fileCount()));
    }

    public record DeleteResponse(int deleted) {}

    public record BatchCollectResponse(int count, int changed) {}

    public record BatchExportResponse(String archiveToken, long archiveExpireSeconds, int count, int fileCount) {}

    private List<DownloadedResponse> filterForGuest(List<DownloadedResponse> items, GuestInviteSession session) {
        if (session == null || items == null || items.isEmpty()) return items;
        List<DownloadedResponse> out = new ArrayList<>(items.size());
        for (DownloadedResponse item : items) {
            if (item == null) continue;
            if (guestAccessGuard.isVisibleToGuest(item.getArtworkId(), session)) {
                out.add(item);
            }
        }
        return out;
    }

    private List<String> parseFormats(String csv) {
        if (csv == null || csv.isBlank()) return null;
        Set<String> allowed = Set.of("jpg", "jpeg", "png", "gif", "webp");
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String v = part.trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty() && allowed.contains(v)) out.add(v);
        }
        return out.isEmpty() ? null : out;
    }

    private List<Long> parseLongList(String csv) {
        if (csv == null || csv.isBlank()) return null;
        List<Long> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            try {
                out.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignore) {
                // 忽略非法项
            }
        }
        return out.isEmpty() ? null : out;
    }
}
