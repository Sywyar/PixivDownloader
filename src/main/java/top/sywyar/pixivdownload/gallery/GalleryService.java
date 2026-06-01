package top.sywyar.pixivdownload.gallery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.download.ArtworkFileLocator;
import top.sywyar.pixivdownload.download.DownloadService;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.download.response.DownloadedResponse;
import top.sywyar.pixivdownload.download.response.PagedHistoryResponse;
import top.sywyar.pixivdownload.i18n.LocalizedException;
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
    private final ArtworkFileLocator artworkFileLocator;

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

    /**
     * 删除单个作品：先删磁盘文件（图片 / 缩略图 / 图库缓存 / 空目录），再删全部 DB 留存数据。
     * 作品不存在时返回 {@code false}。磁盘文件删除失败（被锁定 / 权限不足等）会立即抛出，
     * 不再继续删 DB，避免 DB 与磁盘状态不一致出现孤儿文件。
     */
    public boolean deleteArtwork(long artworkId) {
        ArtworkRecord record = pixivDatabase.getArtwork(artworkId);
        if (record == null) {
            return false;
        }
        if (!artworkFileLocator.deleteArtworkFiles(record)) {
            throw new LocalizedException(HttpStatus.CONFLICT,
                    "gallery.delete.file-failed",
                    "作品 {0} 的磁盘文件未能全部删除，已中止数据库清理",
                    artworkId);
        }
        pixivDatabase.deleteArtwork(artworkId);
        log.info("已删除作品 {} 及其全部留存数据", artworkId);
        return true;
    }

    /** 批量删除作品，返回实际删除的数量。 */
    public int deleteArtworks(Collection<Long> artworkIds) {
        if (artworkIds == null || artworkIds.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (Long id : new LinkedHashSet<>(artworkIds)) {
            if (id == null) continue;
            try {
                if (deleteArtwork(id)) deleted++;
            } catch (Exception e) {
                log.warn("删除作品 {} 失败: {}", id, e.getMessage());
            }
        }
        return deleted;
    }

    private int clampLimit(int limit) {
        if (limit <= 0) return 12;
        return Math.min(limit, 60);
    }

    private List<DownloadedResponse> toResponses(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<ArtworkRecord> fetched = pixivDatabase.getArtworks(ids);
        Map<Long, ArtworkRecord> byId = new HashMap<>(fetched.size());
        for (ArtworkRecord rec : fetched) {
            byId.put(rec.artworkId(), rec);
        }
        List<ArtworkRecord> records = new ArrayList<>(ids.size());
        for (Long id : ids) {
            ArtworkRecord rec = byId.get(id);
            if (rec != null) records.add(rec);
        }
        return toResponses(records);
    }

    private List<DownloadedResponse> toResponses(Collection<ArtworkRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        Map<Long, String> authorNames = resolveAuthorNames(records);
        Map<Long, String> seriesTitles = resolveSeriesTitles(records);
        List<Long> artworkIds = records.stream().map(ArtworkRecord::artworkId).toList();
        Map<Long, List<TagDto>> tagsByArtwork = pixivDatabase.getArtworkTags(artworkIds);
        Set<Long> templateIds = new HashSet<>();
        for (ArtworkRecord rec : records) {
            templateIds.add(rec.fileName() == null ? 1L : rec.fileName());
        }
        Map<Long, String> fileNameTemplates = pixivDatabase.getFileNameTemplates(templateIds);
        List<DownloadedResponse> out = new ArrayList<>(records.size());
        for (ArtworkRecord rec : records) {
            out.add(toDownloadedResponse(rec, authorNames, seriesTitles, tagsByArtwork, fileNameTemplates));
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
        Long fileNameId = artwork.fileName() == null ? 1L : artwork.fileName();
        Map<Long, String> fileNameTemplates = new HashMap<>();
        String template = pixivDatabase.getFileNameTemplate(fileNameId);
        if (template != null) {
            fileNameTemplates.put(fileNameId, template);
        }
        return toDownloadedResponse(
                artwork,
                authorNames,
                seriesTitles,
                Map.of(artwork.artworkId(), pixivDatabase.getArtworkTags(artwork.artworkId())),
                fileNameTemplates);
    }

    private DownloadedResponse toDownloadedResponse(ArtworkRecord artwork, Map<Long, String> authorNames,
                                                    Map<Long, String> seriesTitles,
                                                    Map<Long, List<TagDto>> tagsByArtwork,
                                                    Map<Long, String> fileNameTemplates) {
        List<TagDto> tags = tagsByArtwork.getOrDefault(artwork.artworkId(), List.of());
        Long seriesId = artwork.seriesId();
        String seriesTitle = seriesId != null && seriesId > 0 ? seriesTitles.get(seriesId) : null;
        Long fileNameId = artwork.fileName() == null ? 1L : artwork.fileName();
        String fileNameTemplate = fileNameTemplates.get(fileNameId);
        if (fileNameTemplate == null) {
            fileNameTemplate = pixivDatabase.getFileNameTemplate(fileNameId);
        }
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
                .fileNameTemplate(fileNameTemplate)
                .tags(tags)
                .seriesId(seriesId == null || seriesId <= 0 ? null : seriesId)
                .seriesOrder(artwork.seriesOrder())
                .seriesTitle(seriesTitle)
                .build();
    }
}
