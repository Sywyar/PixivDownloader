package top.sywyar.pixivdownload.core.metadata;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.gallery.GalleryQuery;
import top.sywyar.pixivdownload.gallery.GalleryRepository;
import top.sywyar.pixivdownload.gallery.GuestRestriction;
import top.sywyar.pixivdownload.novel.db.NovelAuthorSummary;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelGalleryRepository;
import top.sywyar.pixivdownload.novel.db.NovelSeries;
import top.sywyar.pixivdownload.novel.db.NovelSeriesSummary;
import top.sywyar.pixivdownload.novel.db.NovelTagOption;
import top.sywyar.pixivdownload.plugin.api.AuthorQuery;
import top.sywyar.pixivdownload.plugin.api.AuthorSummary;
import top.sywyar.pixivdownload.plugin.api.PagedResult;
import top.sywyar.pixivdownload.plugin.api.SeriesNeighbors;
import top.sywyar.pixivdownload.plugin.api.SeriesQuery;
import top.sywyar.pixivdownload.plugin.api.SeriesSummary;
import top.sywyar.pixivdownload.plugin.api.TagOption;
import top.sywyar.pixivdownload.plugin.api.TagQuery;
import top.sywyar.pixivdownload.plugin.api.WorkQuery;
import top.sywyar.pixivdownload.plugin.api.WorkQueryService;
import top.sywyar.pixivdownload.plugin.api.WorkRestriction;
import top.sywyar.pixivdownload.plugin.api.WorkSummary;
import top.sywyar.pixivdownload.plugin.api.WorkType;
import top.sywyar.pixivdownload.series.MangaSeries;
import top.sywyar.pixivdownload.series.MangaSeriesService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link WorkQueryService} 的核心实现：插画侧代理 {@link GalleryRepository}（id 查询 /
 * 标签 / 作者 / 系列目录 / 关联查询）、{@link PixivDatabase}（三态判重）与
 * {@link MangaSeriesService}（系列池补全），小说侧代理
 * {@link NovelGalleryRepository}（访客计数目录）与 {@link NovelDatabase}（三态判重），
 * 查询语义与直接调用被代理类逐条一致。
 *
 * <p>过渡期本类持有对 gallery / novel.db 包内 SQL 仓库的 import：两个仓库今天还住在
 * 各自页面包里，待画廊与小说画廊改走核心接口、仓库收编进核心数据层后消除。
 *
 * <p>尚未接入的方法 × 类型组合（小说侧列表 / 关联与无限制目录）见接口 javadoc，
 * 统一抛 {@link UnsupportedOperationException}。
 */
@Component
@RequiredArgsConstructor
public class CoreWorkQueryService implements WorkQueryService {

    private final GalleryRepository galleryRepository;
    private final NovelGalleryRepository novelGalleryRepository;
    private final PixivDatabase pixivDatabase;
    private final NovelDatabase novelDatabase;
    private final AuthorService authorService;
    private final MangaSeriesService mangaSeriesService;

    @Override
    public PagedResult<WorkSummary> search(WorkQuery query) {
        requireArtwork(query.workType(), "search");
        GalleryRepository.QueryResult result = galleryRepository.findArtworkIds(toGalleryQuery(query));
        int totalPages = (int) Math.ceil((double) result.totalElements() / query.size());
        return new PagedResult<>(
                toSummaries(query.workType(), result.ids()),
                result.totalElements(),
                query.page(),
                query.size(),
                totalPages);
    }

    @Override
    public List<WorkSummary> searchAll(WorkQuery query) {
        requireArtwork(query.workType(), "searchAll");
        return toSummaries(query.workType(), galleryRepository.findAllArtworkIds(toGalleryQuery(query)));
    }

    @Override
    public boolean hasWork(WorkType workType, long workId) {
        return switch (workType) {
            case ARTWORK -> pixivDatabase.hasArtwork(workId);
            case NOVEL -> novelDatabase.hasNovel(workId);
        };
    }

    @Override
    public boolean hasActiveWork(WorkType workType, long workId) {
        return switch (workType) {
            case ARTWORK -> pixivDatabase.hasActiveArtwork(workId);
            case NOVEL -> novelDatabase.hasActiveNovel(workId);
        };
    }

    @Override
    public List<WorkSummary> relatedByTags(WorkType workType, long workId, int limit) {
        requireArtwork(workType, "relatedByTags");
        return toSummaries(workType, galleryRepository.findRelatedByTags(workId, limit));
    }

    @Override
    public List<WorkSummary> byAuthor(WorkType workType, long authorId, long excludeWorkId, int limit) {
        requireArtwork(workType, "byAuthor");
        return toSummaries(workType, galleryRepository.findByAuthor(authorId, excludeWorkId, limit));
    }

    @Override
    public List<WorkSummary> bySeries(WorkType workType, long seriesId, long excludeWorkId, int limit) {
        requireArtwork(workType, "bySeries");
        return toSummaries(workType, galleryRepository.findBySeries(seriesId, excludeWorkId, limit));
    }

    @Override
    public Optional<SeriesNeighbors> seriesNeighbors(WorkType workType, long workId) {
        requireArtwork(workType, "seriesNeighbors");
        GalleryRepository.SeriesNeighbors neighbors = galleryRepository.findSeriesNeighbors(workId);
        if (neighbors == null) {
            return Optional.empty();
        }
        return Optional.of(new SeriesNeighbors(
                neighbors.seriesId(),
                neighbors.seriesTitle(),
                neighbors.currentOrder(),
                toNeighbor(neighbors.prev()),
                toNeighbor(neighbors.next())));
    }

    @Override
    public List<TagOption> tags(TagQuery query) {
        return switch (query.workType()) {
            case ARTWORK -> artworkTags(query);
            case NOVEL -> novelTags(query);
        };
    }

    @Override
    public Optional<TagOption> tagByName(WorkType workType, String name, String translatedName) {
        requireArtwork(workType, "tagByName");
        GalleryRepository.TagOption tag = galleryRepository.findTagByExactName(name, translatedName);
        if (tag == null) {
            return Optional.empty();
        }
        return Optional.of(new TagOption(tag.tagId(), tag.name(), tag.translatedName(), tag.artworkCount()));
    }

    @Override
    public List<AuthorSummary> authors(AuthorQuery query) {
        return switch (query.workType()) {
            case ARTWORK -> artworkAuthors(query);
            case NOVEL -> novelAuthors(query);
        };
    }

    @Override
    public List<SeriesSummary> series(SeriesQuery query) {
        return switch (query.workType()) {
            case ARTWORK -> artworkSeries(query);
            case NOVEL -> novelSeries(query);
        };
    }

    /** 插画作者目录：可见作品计数 + 作者池补名，缺名以 id 字符串兜底。 */
    private List<AuthorSummary> artworkAuthors(AuthorQuery query) {
        List<GalleryRepository.AuthorCount> counts =
                galleryRepository.findAuthorCounts(toGuestRestriction(query.restriction()));
        Set<Long> authorIds = new LinkedHashSet<>();
        for (GalleryRepository.AuthorCount item : counts) {
            authorIds.add(item.authorId());
        }
        Map<Long, String> names = authorService.getAuthorNames(authorIds);
        List<AuthorSummary> out = new ArrayList<>(counts.size());
        for (GalleryRepository.AuthorCount item : counts) {
            out.add(new AuthorSummary(
                    item.authorId(),
                    names.getOrDefault(item.authorId(), String.valueOf(item.authorId())),
                    item.artworkCount()));
        }
        return out;
    }

    /** 插画系列目录：可见作品计数 + 系列池补标题 / 作者，缺行以 id 字符串兜底。 */
    private List<SeriesSummary> artworkSeries(SeriesQuery query) {
        List<GalleryRepository.SeriesCount> counts =
                galleryRepository.findSeriesCounts(toGuestRestriction(query.restriction()));
        Set<Long> seriesIds = new LinkedHashSet<>();
        for (GalleryRepository.SeriesCount item : counts) {
            seriesIds.add(item.seriesId());
        }
        Map<Long, MangaSeries> seriesById = new LinkedHashMap<>();
        Set<Long> authorIds = new LinkedHashSet<>();
        for (MangaSeries seriesRow : mangaSeriesService.getSeriesByIds(seriesIds)) {
            seriesById.put(seriesRow.seriesId(), seriesRow);
            if (seriesRow.authorId() != null && seriesRow.authorId() > 0) {
                authorIds.add(seriesRow.authorId());
            }
        }
        Map<Long, String> authorNames = authorService.getAuthorNames(authorIds);
        List<SeriesSummary> out = new ArrayList<>(counts.size());
        for (GalleryRepository.SeriesCount item : counts) {
            MangaSeries seriesRow = seriesById.get(item.seriesId());
            String title = seriesRow == null ? String.valueOf(item.seriesId()) : seriesRow.title();
            Long authorId = seriesRow == null ? null : seriesRow.authorId();
            String authorName = authorId == null ? null : authorNames.get(authorId);
            out.add(new SeriesSummary(item.seriesId(), title, authorId, authorName, item.artworkCount()));
        }
        return out;
    }

    private List<AuthorSummary> novelAuthors(AuthorQuery query) {
        GuestRestriction restriction = requireNovelRestriction(query.restriction(), "authors");
        List<NovelAuthorSummary> counts = novelGalleryRepository.findVisibleNovelAuthorCounts(restriction);
        Set<Long> authorIds = new LinkedHashSet<>();
        for (NovelAuthorSummary item : counts) {
            authorIds.add(item.authorId());
        }
        Map<Long, String> names = authorService.getAuthorNames(authorIds);
        List<AuthorSummary> out = new ArrayList<>(counts.size());
        for (NovelAuthorSummary item : counts) {
            out.add(new AuthorSummary(
                    item.authorId(),
                    names.getOrDefault(item.authorId(), String.valueOf(item.authorId())),
                    item.novelCount()));
        }
        return out;
    }

    private List<SeriesSummary> novelSeries(SeriesQuery query) {
        GuestRestriction restriction = requireNovelRestriction(query.restriction(), "series");
        List<NovelSeriesSummary> counts = novelGalleryRepository.findVisibleNovelSeriesCounts(restriction);
        Set<Long> seriesIds = new LinkedHashSet<>();
        for (NovelSeriesSummary item : counts) {
            seriesIds.add(item.seriesId());
        }
        Map<Long, NovelSeries> seriesById = new LinkedHashMap<>();
        Set<Long> authorIds = new LinkedHashSet<>();
        for (NovelSeries seriesRow : novelDatabase.getSeriesByIds(seriesIds)) {
            seriesById.put(seriesRow.seriesId(), seriesRow);
            if (seriesRow.authorId() != null && seriesRow.authorId() > 0) {
                authorIds.add(seriesRow.authorId());
            }
        }
        Map<Long, String> authorNames = authorService.getAuthorNames(authorIds);
        List<SeriesSummary> out = new ArrayList<>(counts.size());
        for (NovelSeriesSummary item : counts) {
            NovelSeries seriesRow = seriesById.get(item.seriesId());
            String title = seriesRow == null ? String.valueOf(item.seriesId()) : seriesRow.title();
            Long authorId = seriesRow == null ? null : seriesRow.authorId();
            String authorName = authorId == null ? null : authorNames.get(authorId);
            out.add(new SeriesSummary(item.seriesId(), title, authorId, authorName, item.novelCount()));
        }
        return out;
    }

    /** 插画标签目录：全量使用计数 + 访客可见性过滤，钳制与过滤语义与画廊页既有实现一致。 */
    private List<TagOption> artworkTags(TagQuery query) {
        int clamped = query.limit() <= 0 ? 500 : Math.min(query.limit(), 2000);
        List<GalleryRepository.TagOption> all = galleryRepository.findTagsWithCounts(query.search(), clamped);
        GuestRestriction restriction = toGuestRestriction(query.restriction());
        List<TagOption> out = new ArrayList<>(all.size());
        Set<Long> visible = restriction == null ? null : galleryRepository.findVisibleTagIds(restriction);
        for (GalleryRepository.TagOption tag : all) {
            if (visible != null && !visible.contains(tag.tagId())) {
                continue;
            }
            out.add(new TagOption(tag.tagId(), tag.name(), tag.translatedName(), tag.artworkCount()));
        }
        return out;
    }

    private List<TagOption> novelTags(TagQuery query) {
        GuestRestriction restriction = requireNovelRestriction(query.restriction(), "tags");
        List<NovelTagOption> tags = novelGalleryRepository.findVisibleNovelTagCounts(
                restriction, query.search(), query.limit());
        List<TagOption> out = new ArrayList<>(tags.size());
        for (NovelTagOption tag : tags) {
            out.add(new TagOption(tag.tagId(), tag.name(), tag.translatedName(), tag.novelCount()));
        }
        return out;
    }

    private static List<WorkSummary> toSummaries(WorkType workType, List<Long> ids) {
        List<WorkSummary> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            out.add(new WorkSummary(workType, id));
        }
        return out;
    }

    private static SeriesNeighbors.Neighbor toNeighbor(GalleryRepository.Neighbor neighbor) {
        if (neighbor == null) {
            return null;
        }
        return new SeriesNeighbors.Neighbor(neighbor.artworkId(), neighbor.title(), neighbor.seriesOrder());
    }

    private GalleryQuery toGalleryQuery(WorkQuery query) {
        return GalleryQuery.builder()
                .page(query.page())
                .size(query.size())
                .sort(query.sort())
                .order(query.order())
                .search(query.search())
                .searchType(query.searchType())
                .r18(query.r18())
                .ai(query.ai())
                .formats(query.formats())
                .collectionIds(query.collectionIds())
                .tagIds(query.tagIds())
                .excludedTagIds(query.excludedTagIds())
                .optionalTagIds(query.optionalTagIds())
                .authorIds(query.authorIds())
                .excludedAuthorIds(query.excludedAuthorIds())
                .optionalAuthorIds(query.optionalAuthorIds())
                .seriesIds(query.seriesIds())
                .excludedSeriesIds(query.excludedSeriesIds())
                .guestRestriction(toGuestRestriction(query.restriction()))
                .build();
    }

    private static GuestRestriction toGuestRestriction(WorkRestriction restriction) {
        if (restriction == null) {
            return null;
        }
        return new GuestRestriction(
                restriction.allowedXRestricts(),
                restriction.tagUnrestricted(),
                restriction.tagIds(),
                restriction.authorUnrestricted(),
                restriction.authorIds());
    }

    /** 小说目录查询今天只有访客投影可代理：无限制目录是小说画廊侧内存实现，缺投影即缺口。 */
    private static GuestRestriction requireNovelRestriction(WorkRestriction restriction, String method) {
        if (restriction == null) {
            throw unsupported(WorkType.NOVEL,
                    method + "（无限制目录）：小说侧无限制目录为小说画廊内存实现，待小说画廊改走核心接口时下沉接入");
        }
        return toGuestRestriction(restriction);
    }

    private static void requireArtwork(WorkType workType, String method) {
        if (workType != WorkType.ARTWORK) {
            throw unsupported(workType, method + "：小说列表与关联查询为小说画廊侧内存实现，待小说画廊改走核心接口时下沉接入");
        }
    }

    private static UnsupportedOperationException unsupported(WorkType workType, String detail) {
        return new UnsupportedOperationException("WorkQueryService 尚未接入 " + workType + " 的 " + detail);
    }
}
