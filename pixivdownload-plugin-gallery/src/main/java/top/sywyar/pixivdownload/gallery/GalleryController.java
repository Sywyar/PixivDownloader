package top.sywyar.pixivdownload.gallery;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.gallery.web.GalleryArtworkResponse;
import top.sywyar.pixivdownload.gallery.web.GalleryPageResponse;
import top.sywyar.pixivdownload.gallery.web.GalleryTagOptionResponse;
import top.sywyar.pixivdownload.gallery.web.GalleryWorkQueryFactory;
import top.sywyar.pixivdownload.core.archive.ArchiveExportResult;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.query.SeriesNeighbors;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;

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
    private final WorkVisibilityService workVisibilityService;

    @GetMapping("/artworks")
    public GalleryPageResponse listArtworks(
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
            WorkVisibilityScope visibilityScope) {

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
        WorkRestriction restriction = visibilityScope.restrictionFor(WorkType.ARTWORK);
        WorkQuery query = GalleryWorkQueryFactory.create(
                page, size, sort, order, search, searchType, r18, ai,
                parseFormats(format),
                parseLongList(collectionIds),
                parseLongList(tagIds),
                parseLongList(notTagIds),
                parseLongList(orTagIds),
                requiredAuthorIds,
                parseLongList(notAuthorIds),
                parseLongList(orAuthorIds),
                requiredSeriesIds,
                parseLongList(notSeriesIds),
                restriction);
        return galleryService.query(query);
    }

    @GetMapping("/tags")
    public Map<String, Object> listTags(
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "500") int limit,
            WorkVisibilityScope visibilityScope) {
        WorkRestriction restriction = visibilityScope.restrictionFor(WorkType.ARTWORK);
        List<GalleryTagOptionResponse> tags = galleryService.listTags(search, limit, restriction);
        return Map.of("tags", tags);
    }

    @GetMapping("/tags/lookup")
    public ResponseEntity<GalleryTagOptionResponse> findTag(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String translatedName) {
        GalleryTagOptionResponse tag = galleryService.findTag(name, translatedName);
        return tag == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(tag);
    }

    @GetMapping("/artwork/{artworkId}")
    public ResponseEntity<GalleryArtworkResponse> artwork(@PathVariable long artworkId,
                                                          WorkVisibilityScope visibilityScope) {
        workVisibilityService.requireVisible(visibilityScope, WorkType.ARTWORK, artworkId);
        GalleryArtworkResponse resp = galleryService.findArtwork(artworkId);
        return resp == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(resp);
    }

    @GetMapping("/artwork/{artworkId}/related")
    public ResponseEntity<List<GalleryArtworkResponse>> related(
            @PathVariable long artworkId,
            @RequestParam(defaultValue = "12") int limit,
            WorkVisibilityScope visibilityScope) {
        workVisibilityService.requireVisible(visibilityScope, WorkType.ARTWORK, artworkId);
        List<GalleryArtworkResponse> all = galleryService.related(artworkId, limit);
        return ResponseEntity.ok(filterForGuest(all, visibilityScope));
    }

    @GetMapping("/artwork/{artworkId}/by-author")
    public ResponseEntity<List<GalleryArtworkResponse>> byAuthor(
            @PathVariable long artworkId,
            @RequestParam(defaultValue = "12") int limit,
            WorkVisibilityScope visibilityScope) {
        workVisibilityService.requireVisible(visibilityScope, WorkType.ARTWORK, artworkId);
        List<GalleryArtworkResponse> all = galleryService.byAuthor(artworkId, limit);
        return ResponseEntity.ok(filterForGuest(all, visibilityScope));
    }

    @GetMapping("/artwork/{artworkId}/by-series")
    public ResponseEntity<List<GalleryArtworkResponse>> bySeries(
            @PathVariable long artworkId,
            @RequestParam(defaultValue = "30") int limit,
            WorkVisibilityScope visibilityScope) {
        workVisibilityService.requireVisible(visibilityScope, WorkType.ARTWORK, artworkId);
        List<GalleryArtworkResponse> all = galleryService.bySeries(artworkId, limit);
        return ResponseEntity.ok(filterForGuest(all, visibilityScope));
    }

    @GetMapping("/artwork/{artworkId}/series")
    public ResponseEntity<SeriesNavResponse> seriesNav(
            @PathVariable long artworkId,
            WorkVisibilityScope visibilityScope) {
        workVisibilityService.requireVisible(visibilityScope, WorkType.ARTWORK, artworkId);
        boolean restricted = visibilityScope.restrictionFor(WorkType.ARTWORK) != null;
        SeriesNeighbors neighbors = galleryService.seriesNeighbors(artworkId);
        if (neighbors == null) {
            return ResponseEntity.ok(new SeriesNavResponse(null, null, null, null, null));
        }
        SeriesNavResponse.NeighborView prev = neighbors.prev() == null
                ? null
                : (restricted && !workVisibilityService.isVisible(
                        visibilityScope, WorkType.ARTWORK, neighbors.prev().workId())
                    ? null
                    : new SeriesNavResponse.NeighborView(
                        neighbors.prev().workId(),
                        neighbors.prev().title(),
                        neighbors.prev().seriesOrder()));
        SeriesNavResponse.NeighborView next = neighbors.next() == null
                ? null
                : (restricted && !workVisibilityService.isVisible(
                        visibilityScope, WorkType.ARTWORK, neighbors.next().workId())
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
        ArchiveExportResult result = galleryBatchService.exportArtworks(
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

    private List<GalleryArtworkResponse> filterForGuest(List<GalleryArtworkResponse> items,
                                                        WorkVisibilityScope visibilityScope) {
        if (items == null || items.isEmpty()
                || visibilityScope.restrictionFor(WorkType.ARTWORK) == null) {
            return items;
        }
        List<GalleryArtworkResponse> out = new ArrayList<>(items.size());
        for (GalleryArtworkResponse item : items) {
            if (item == null) continue;
            if (workVisibilityService.isVisible(
                    visibilityScope, WorkType.ARTWORK, item.artworkId())) {
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
