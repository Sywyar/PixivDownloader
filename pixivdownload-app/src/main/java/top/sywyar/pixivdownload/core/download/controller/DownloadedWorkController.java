package top.sywyar.pixivdownload.core.download.controller;

import top.sywyar.pixivdownload.series.MangaSeriesService;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityScopeFactory;
import top.sywyar.pixivdownload.core.metadata.artwork.GalleryRepository;
import top.sywyar.pixivdownload.core.download.ArtworkMetadataRecoveryService;
import top.sywyar.pixivdownload.core.download.DownloadStatisticsService;
import top.sywyar.pixivdownload.core.download.DownloadedArtworkService;
import top.sywyar.pixivdownload.core.download.request.ArtworkBatchRequest;
import top.sywyar.pixivdownload.core.download.request.RecoverMetadataRequest;
import top.sywyar.pixivdownload.core.download.response.BatchArtworksResponse;
import top.sywyar.pixivdownload.core.download.response.DownloadedResponse;
import top.sywyar.pixivdownload.core.download.response.HistoryResponse;
import top.sywyar.pixivdownload.core.download.response.PagedHistoryResponse;
import top.sywyar.pixivdownload.core.download.response.StatisticsResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Gatherers;

@HttpApiGroup("core-library")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DownloadedWorkController {
    private static final int METADATA_QUERY_BATCH_SIZE = 500;

    private final DownloadedArtworkService downloadedArtworkService;
    private final ArtworkMetadataRecoveryService artworkMetadataRecoveryService;
    private final DownloadStatisticsService downloadStatisticsService;
    private final PixivDatabase pixivDatabase;
    private final AuthorService authorService;
    private final MangaSeriesService mangaSeriesService;
    private final GuestAccessGuard guestAccessGuard;
    private final GalleryRepository galleryRepository;
    private final AppMessages messages;

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content)
    @GetMapping({"/downloaded/{artworkId}", "/library/artworks/{artworkId}"})
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

    public record PartialFiles(long artworkId, int totalPages, String folder, Map<Integer, List<String>> files) {}

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content)
    @GetMapping("/library/artworks/{artworkId}/partial")
    public ResponseEntity<PartialFiles> partial(@PathVariable long artworkId, HttpServletRequest request)
            throws java.io.IOException {
        guestAccessGuard.requireVisible(request, artworkId);
        try (var lease = top.sywyar.pixivdownload.core.work.service.WorkFileLock.acquire(WorkType.ARTWORK, artworkId)) {
            var partial = pixivDatabase.partialArtwork(artworkId);
            if (partial == null) return ResponseEntity.notFound().build();
            return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(
                    new PartialFiles(artworkId, partial.totalPages(), partial.folder(), partial.availableFiles()));
        }
    }

    /**
     * 使用调用方提供的元数据逐项补缺。
     * 无记录时须提供完整页数，且默认命名的文件全部齐备，才能登记；GET 查询不执行恢复。
     */
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse.Basic.class)))
    @PostMapping({"/downloaded/{artworkId}/recover-metadata", "/library/artworks/{artworkId}/recover-metadata"})
    public ResponseEntity<DownloadedResponse> recoverMetadata(
            @PathVariable Long artworkId,
            @Valid @RequestBody RecoverMetadataRequest body,
            HttpServletRequest httpRequest) {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        ArtworkRecord artwork = artworkMetadataRecoveryService.recoverMetadata(artworkId, body);
        if (artwork == null) {
            return ResponseEntity.status(400).build();
        }
        return ResponseEntity.ok(toDownloadedResponses(List.of(artwork)).getFirst());
    }

    @PostMapping("/downloaded/batch")
    public ResponseEntity<BatchArtworksResponse> getBatchArtworks(@RequestBody ArtworkBatchRequest request) {
        List<Long> requestedIds = request == null || request.getArtworkIds() == null
                ? List.of()
                : request.getArtworkIds().stream().filter(java.util.Objects::nonNull).toList();
        Map<Long, ArtworkRecord> recordsById = new HashMap<>();
        for (ArtworkRecord artwork : pixivDatabase.getArtworks(requestedIds)) {
            recordsById.put(artwork.artworkId(), artwork);
        }

        List<ArtworkRecord> artworks = new ArrayList<>();
        List<Long> deletedArtworkIds = new ArrayList<>();
        for (Long artworkId : requestedIds) {
            ArtworkRecord artwork = recordsById.get(artworkId);
            if (artwork == null) {
                continue;
            }
            if (artwork.deleted()) {
                if (request.isIncludeDeleted()) {
                    deletedArtworkIds.add(artworkId);
                }
                continue;
            }
            artworks.add(artwork);
        }

        return ResponseEntity.ok(new BatchArtworksResponse(toDownloadedResponses(artworks), deletedArtworkIds));
    }

    @GetMapping("/downloaded/statistics")
    public ResponseEntity<StatisticsResponse> getStatistics(HttpServletRequest httpRequest) {
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        if (session != null) {
            return ResponseEntity.ok(getGuestStatistics(session));
        }
        return ResponseEntity.ok(downloadStatisticsService.getStatistics());
    }

    @io.swagger.v3.oas.annotations.Operation(deprecated = true)
    @ApiResponse(responseCode = "410", content = @Content)
    @Parameter(name = "artworkId", in = io.swagger.v3.oas.annotations.enums.ParameterIn.PATH,
            required = true, schema = @Schema(type = "string"))
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.GONE)
    @PostMapping("/library/artworks/{artworkId}/move")
    public void moveArtWork() {}

    @GetMapping("/downloaded/history")
    public ResponseEntity<HistoryResponse> getHistory(HttpServletRequest httpRequest) {
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        if (session == null) {
            return ResponseEntity.ok(new HistoryResponse(downloadedArtworkService.getDownloadedRecord()));
        }
        return ResponseEntity.ok(new HistoryResponse(getVisibleArtworkIds(session).stream()
                .map(String::valueOf)
                .toList()));
    }

    @GetMapping("/downloaded/history/paged")
    public ResponseEntity<PagedHistoryResponse> getPagedHistory(
            @Parameter(example = "0", schema = @Schema(type = "integer", format = "int32", defaultValue = "0"))
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "time") String sort,
            HttpServletRequest httpRequest) {
        page = Math.max(0, page);
        size = Math.max(1, size);
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        if (session != null) {
            return ResponseEntity.ok(getGuestPagedHistory(page, Math.min(size, 200), sort, session));
        }
        long totalElements = downloadedArtworkService.getArtworkCount();
        List<Long> artWorkIds = "author_id".equals(sort)
                ? downloadedArtworkService.getSortAuthorArtworkPaged(page, size)
                : downloadedArtworkService.getSortTimeArtworkPaged(page, size);
        List<DownloadedResponse> downloadedResponses = loadDownloadedResponses(artWorkIds);
        int totalPages = (int) Math.ceil((double) totalElements / size);
        return ResponseEntity.ok(new PagedHistoryResponse(downloadedResponses, totalElements, page, size, totalPages));
    }

    public DownloadedResponse getArtWorkDownloadedResponse(Long artworkId) {
        return getArtWorkDownloadedResponse(artworkId, false);
    }

    public DownloadedResponse getArtWorkDownloadedResponse(Long artworkId, boolean verifyFiles) {
        ArtworkRecord artwork = downloadedArtworkService.getDownloadedRecord(artworkId, verifyFiles);
        if (artwork == null) {
            return null;
        }
        return toDownloadedResponses(List.of(artwork)).getFirst();
    }

    private StatisticsResponse getGuestStatistics(GuestInviteSession session) {
        GalleryRepository.GuestStatistics stats = galleryRepository.findGuestStatistics(
                GuestWorkVisibilityScopeFactory.artworkRestriction(session));
        return new StatisticsResponse(true, stats.artworks(), stats.images(), stats.moved(), stats.artworks(), stats.images(),
                messages.get("download.statistics.success"));
    }

    private PagedHistoryResponse getGuestPagedHistory(int page, int size, String sort,
                                                      GuestInviteSession session) {
        WorkQuery query = WorkQuery.builder(WorkType.ARTWORK)
                .page(page)
                .size(size)
                .sort("author_id".equals(sort) ? "authorId" : "date")
                .order("desc")
                .r18("any")
                .ai("any")
                .restriction(GuestWorkVisibilityScopeFactory.artworkRestriction(session))
                .build();
        GalleryRepository.QueryResult result = galleryRepository.findArtworkIds(query);
        List<DownloadedResponse> responses = loadDownloadedResponses(result.ids());
        long total = result.totalElements();
        int totalPages = (int) Math.ceil((double) total / size);
        return new PagedHistoryResponse(responses, total, page, size, totalPages);
    }

    private List<DownloadedResponse> loadDownloadedResponses(List<Long> artworkIds) {
        return artworkIds.stream()
                .map(downloadedArtworkService::getDownloadedRecord)
                .filter(Objects::nonNull)
                .gather(Gatherers.windowFixed(METADATA_QUERY_BATCH_SIZE))
                .flatMap(artworks -> toDownloadedResponses(artworks).stream())
                .toList();
    }

    private List<Long> getVisibleArtworkIds(GuestInviteSession session) {
        WorkQuery query = WorkQuery.builder(WorkType.ARTWORK)
                .page(0)
                .size(Integer.MAX_VALUE)
                .sort("date")
                .order("desc")
                .r18("any")
                .ai("any")
                .restriction(GuestWorkVisibilityScopeFactory.artworkRestriction(session))
                .build();
        return galleryRepository.findArtworkIds(query).ids();
    }

    private List<DownloadedResponse> toDownloadedResponses(List<ArtworkRecord> artworks) {
        if (artworks.isEmpty()) return List.of();
        Map<Long, String> authorNames = resolveAuthorNames(artworks);
        Map<Long, String> seriesTitles = mangaSeriesService.titlesForArtworks(artworks);
        List<Long> activeArtworkIds = artworks.stream().map(ArtworkRecord::artworkId).toList();
        Map<Long, List<TagDto>> tagsByArtworkId = pixivDatabase.getArtworkTags(activeArtworkIds);
        Set<Long> fileNameIds = new HashSet<>();
        for (ArtworkRecord artwork : artworks) {
            fileNameIds.add(resolveFileNameId(artwork));
        }
        Map<Long, String> fileNameTemplates = pixivDatabase.getFileNameTemplates(fileNameIds);
        return artworks.stream()
                .map(artwork -> toDownloadedResponse(
                        artwork, authorNames, seriesTitles, tagsByArtworkId, fileNameTemplates))
                .toList();
    }

    private DownloadedResponse toDownloadedResponse(
            ArtworkRecord artwork,
            Map<Long, String> authorNames,
            Map<Long, String> seriesTitles,
            Map<Long, List<TagDto>> tagsByArtworkId,
            Map<Long, String> fileNameTemplates) {
        long fileNameId = resolveFileNameId(artwork);
        Long seriesId = artwork.seriesId();
        boolean inSeries = seriesId != null && seriesId > 0;
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
                .seriesId(inSeries ? seriesId : null)
                .seriesOrder(inSeries ? artwork.seriesOrder() : null)
                .seriesTitle(inSeries ? seriesTitles.get(seriesId) : null)
                .description(artwork.description())
                .fileName(artwork.fileName())
                .fileNameTemplate(PixivWorkFileNameFormatter.normalizeTemplate(fileNameTemplates.get(fileNameId)))
                .tags(tagsByArtworkId.getOrDefault(artwork.artworkId(), List.of()))
                .fileManagement(artwork.fileManagement())
                .deleted(artwork.deleted())
                .build();
    }

    private long resolveFileNameId(ArtworkRecord artwork) {
        return artwork.fileName() == null
                ? PixivDatabase.DEFAULT_FILE_NAME_TEMPLATE_ID
                : artwork.fileName();
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
