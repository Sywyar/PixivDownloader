package top.sywyar.pixivdownload.core.download.controller;

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
import top.sywyar.pixivdownload.core.db.ArtworkFileNameFormatter;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.core.metadata.GuestRestriction;
import top.sywyar.pixivdownload.core.metadata.artwork.GalleryQuery;
import top.sywyar.pixivdownload.core.metadata.artwork.GalleryRepository;
import top.sywyar.pixivdownload.core.download.ArtworkMetadataRecoveryService;
import top.sywyar.pixivdownload.core.download.ArtworkMoveService;
import top.sywyar.pixivdownload.core.download.DownloadStatisticsService;
import top.sywyar.pixivdownload.core.download.DownloadedArtworkService;
import top.sywyar.pixivdownload.core.download.request.ArtworkBatchRequest;
import top.sywyar.pixivdownload.core.download.request.MoveArtworkRequest;
import top.sywyar.pixivdownload.core.download.request.RecoverMetadataRequest;
import top.sywyar.pixivdownload.core.download.response.ArtworkIdResponse;
import top.sywyar.pixivdownload.core.download.response.BatchArtworksResponse;
import top.sywyar.pixivdownload.core.download.response.DownloadResponse;
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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DownloadedWorkController {

    private final DownloadedArtworkService downloadedArtworkService;
    private final ArtworkMetadataRecoveryService artworkMetadataRecoveryService;
    private final DownloadStatisticsService downloadStatisticsService;
    private final ArtworkMoveService artworkMoveService;
    private final PixivDatabase pixivDatabase;
    private final AuthorService authorService;
    private final GuestAccessGuard guestAccessGuard;
    private final GalleryRepository galleryRepository;
    private final AppMessages messages;

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
        ArtworkRecord artwork = artworkMetadataRecoveryService.recoverMetadata(artworkId, body);
        if (artwork == null) {
            return ResponseEntity.status(400).build();
        }
        return ResponseEntity.ok(toDownloadedResponse(artwork, resolveAuthorNames(List.of(artwork))));
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

        Map<Long, String> authorNames = resolveAuthorNames(artworks);
        List<Long> activeArtworkIds = artworks.stream().map(ArtworkRecord::artworkId).toList();
        Map<Long, List<TagDto>> tagsByArtworkId = pixivDatabase.getArtworkTags(activeArtworkIds);
        Set<Long> fileNameIds = new HashSet<>();
        for (ArtworkRecord artwork : artworks) {
            fileNameIds.add(resolveFileNameId(artwork));
        }
        Map<Long, String> fileNameTemplates = pixivDatabase.getFileNameTemplates(fileNameIds);
        List<DownloadedResponse> downloadedResponses = artworks.stream()
                .map(artwork -> toDownloadedResponse(
                        artwork, authorNames, tagsByArtworkId, fileNameTemplates))
                .toList();
        return ResponseEntity.ok(new BatchArtworksResponse(downloadedResponses, deletedArtworkIds));
    }

    @GetMapping("/downloaded/statistics")
    public ResponseEntity<StatisticsResponse> getStatistics(HttpServletRequest httpRequest) {
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        if (session != null) {
            return ResponseEntity.ok(getGuestStatistics(session));
        }
        return ResponseEntity.ok(downloadStatisticsService.getStatistics());
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
        artworkMoveService.moveArtWork(artworkId, movePath, request.getMoveTime(), presetRoot);
        return ResponseEntity.ok(DownloadResponse.builder()
                .success(true)
                .message(messages.get("download.move.record-attempted"))
                .build());
    }

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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "time") String sort,
            HttpServletRequest httpRequest) {
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        if (session != null) {
            return ResponseEntity.ok(getGuestPagedHistory(page, size, sort, session));
        }
        long totalElements = downloadedArtworkService.getArtworkCount();
        List<Long> artWorkIds = "author_id".equals(sort)
                ? downloadedArtworkService.getSortAuthorArtworkPaged(page, size)
                : downloadedArtworkService.getSortTimeArtworkPaged(page, size);
        List<ArtworkRecord> artworks = new LinkedList<>();

        for (Long artworkId : artWorkIds) {
            ArtworkRecord artwork = downloadedArtworkService.getDownloadedRecord(artworkId);
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

    public DownloadedResponse getArtWorkDownloadedResponse(Long artworkId) {
        return getArtWorkDownloadedResponse(artworkId, false);
    }

    public DownloadedResponse getArtWorkDownloadedResponse(Long artworkId, boolean verifyFiles) {
        ArtworkRecord artwork = downloadedArtworkService.getDownloadedRecord(artworkId, verifyFiles);
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
            ArtworkRecord artwork = downloadedArtworkService.getDownloadedRecord(artworkId);
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
        long fileNameId = resolveFileNameId(artwork);
        Map<Long, String> fileNameTemplates = new HashMap<>();
        fileNameTemplates.put(fileNameId, pixivDatabase.getFileNameTemplate(fileNameId));
        return toDownloadedResponse(
                artwork,
                authorNames,
                tags == null ? Map.of() : Map.of(artwork.artworkId(), tags),
                fileNameTemplates);
    }

    private DownloadedResponse toDownloadedResponse(
            ArtworkRecord artwork,
            Map<Long, String> authorNames,
            Map<Long, List<TagDto>> tagsByArtworkId,
            Map<Long, String> fileNameTemplates) {
        long fileNameId = resolveFileNameId(artwork);
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
                .fileNameTemplate(ArtworkFileNameFormatter.normalizeTemplate(fileNameTemplates.get(fileNameId)))
                .tags(tagsByArtworkId.getOrDefault(artwork.artworkId(), List.of()))
                .deleted(artwork.deleted())
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
