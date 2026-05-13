package top.sywyar.pixivdownload.gallery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.download.DownloadService;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.download.response.DownloadedResponse;
import top.sywyar.pixivdownload.download.response.PagedHistoryResponse;
import top.sywyar.pixivdownload.series.MangaSeries;
import top.sywyar.pixivdownload.series.MangaSeriesService;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GalleryService {

    private final GalleryRepository galleryRepository;
    private final PixivDatabase pixivDatabase;
    private final DownloadService downloadService;
    private final AuthorService authorService;
    private final MangaSeriesService mangaSeriesService;

    public PagedHistoryResponse query(GalleryQuery query) {
        GalleryRepository.QueryResult result = galleryRepository.findArtworkIds(query);
        List<DownloadedResponse> content = toResponses(result.ids());
        int totalPages = (int) Math.ceil((double) result.totalElements() / query.getSize());
        return new PagedHistoryResponse(content, result.totalElements(),
                query.getPage(), query.getSize(), totalPages);
    }

    public List<GalleryRepository.TagOption> listTags(String search, int limit, GuestRestriction restriction) {
        int clamped = limit <= 0 ? 500 : Math.min(limit, 2000);
        List<GalleryRepository.TagOption> all = galleryRepository.findTagsWithCounts(search, clamped);
        if (restriction == null) return all;
        java.util.Set<Long> visible = galleryRepository.findVisibleTagIds(restriction);
        List<GalleryRepository.TagOption> out = new ArrayList<>(visible.size());
        for (GalleryRepository.TagOption opt : all) {
            if (visible.contains(opt.tagId())) out.add(opt);
        }
        return out;
    }

    public List<GalleryRepository.TagOption> listTags(String search, int limit) {
        return listTags(search, limit, null);
    }

    public GalleryRepository.TagOption findTag(String name, String translatedName) {
        return galleryRepository.findTagByExactName(name, translatedName);
    }

    public DownloadedResponse findArtwork(long artworkId) {
        ArtworkRecord rec = downloadService.getDownloadedRecord(artworkId);
        if (rec == null) return null;
        Map<Long, String> authorNames = resolveAuthorNames(List.of(rec));
        Map<Long, String> seriesTitles = resolveSeriesTitles(List.of(rec));
        return toDownloadedResponse(rec, authorNames, seriesTitles);
    }

    public List<DownloadedResponse> related(long artworkId, int limit) {
        int clamped = clampLimit(limit);
        List<Long> ids = galleryRepository.findRelatedByTags(artworkId, clamped);
        return toResponses(ids);
    }

    public List<DownloadedResponse> byAuthor(long artworkId, int limit) {
        ArtworkRecord base = downloadService.getDownloadedRecord(artworkId);
        if (base == null || base.authorId() == null) {
            return List.of();
        }
        int clamped = clampLimit(limit);
        List<Long> ids = galleryRepository.findByAuthor(base.authorId(), artworkId, clamped);
        return toResponses(ids);
    }

    public List<DownloadedResponse> bySeries(long artworkId, int limit) {
        ArtworkRecord base = downloadService.getDownloadedRecord(artworkId);
        if (base == null || base.seriesId() == null || base.seriesId() <= 0) {
            return List.of();
        }
        int clamped = clampLimit(limit);
        List<Long> ids = galleryRepository.findBySeries(base.seriesId(), artworkId, clamped);
        return toResponses(ids);
    }

    public GalleryRepository.SeriesNeighbors seriesNeighbors(long artworkId) {
        return galleryRepository.findSeriesNeighbors(artworkId);
    }

    private int clampLimit(int limit) {
        if (limit <= 0) return 12;
        return Math.min(limit, 60);
    }

    private List<DownloadedResponse> toResponses(List<Long> ids) {
        List<ArtworkRecord> records = new ArrayList<>(ids.size());
        for (Long id : ids) {
            ArtworkRecord rec = downloadService.getDownloadedRecord(id);
            if (rec != null) records.add(rec);
        }
        Map<Long, String> authorNames = resolveAuthorNames(records);
        Map<Long, String> seriesTitles = resolveSeriesTitles(records);
        List<DownloadedResponse> out = new ArrayList<>(records.size());
        for (ArtworkRecord rec : records) {
            out.add(toDownloadedResponse(rec, authorNames, seriesTitles));
        }
        return out;
    }

    private Map<Long, String> resolveAuthorNames(Collection<ArtworkRecord> records) {
        Set<Long> authorIds = new HashSet<>();
        for (ArtworkRecord rec : records) {
            if (rec != null && rec.authorId() != null) authorIds.add(rec.authorId());
        }
        return authorService.getAuthorNames(authorIds);
    }

    private Map<Long, String> resolveSeriesTitles(Collection<ArtworkRecord> records) {
        Set<Long> seriesIds = new HashSet<>();
        for (ArtworkRecord rec : records) {
            if (rec != null && rec.seriesId() != null && rec.seriesId() > 0) {
                seriesIds.add(rec.seriesId());
            }
        }
        if (seriesIds.isEmpty()) return Collections.emptyMap();
        Map<Long, String> out = new HashMap<>(seriesIds.size());
        for (MangaSeries series : mangaSeriesService.getSeriesByIds(seriesIds)) {
            out.put(series.seriesId(), series.title());
        }
        return out;
    }

    private DownloadedResponse toDownloadedResponse(ArtworkRecord artwork, Map<Long, String> authorNames,
                                                    Map<Long, String> seriesTitles) {
        List<TagDto> tags = pixivDatabase.getArtworkTags(artwork.artworkId());
        Long seriesId = artwork.seriesId();
        String seriesTitle = seriesId != null && seriesId > 0 ? seriesTitles.get(seriesId) : null;
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
                .fileNameTemplate(pixivDatabase.getFileNameTemplate(artwork.fileName() == null ? 1L : artwork.fileName()))
                .tags(tags)
                .seriesId(seriesId == null || seriesId <= 0 ? null : seriesId)
                .seriesOrder(artwork.seriesOrder())
                .seriesTitle(seriesTitle)
                .build();
    }
}
