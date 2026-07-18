package top.sywyar.pixivdownload.core.metadata;

import top.sywyar.pixivdownload.core.metadata.artwork.GalleryQuery;
import top.sywyar.pixivdownload.core.metadata.artwork.GalleryRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelAuthorSummary;
import top.sywyar.pixivdownload.core.metadata.novel.NovelGalleryRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRow;
import top.sywyar.pixivdownload.core.metadata.novel.NovelSeriesTitleRow;
import top.sywyar.pixivdownload.core.metadata.novel.NovelTagOption;
import top.sywyar.pixivdownload.core.metadata.novel.NovelWorkSearch;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.plugin.api.work.query.AuthorQuery;
import top.sywyar.pixivdownload.plugin.api.work.query.AuthorSummary;
import top.sywyar.pixivdownload.plugin.api.work.model.PagedResult;
import top.sywyar.pixivdownload.plugin.api.work.query.SeriesNeighbors;
import top.sywyar.pixivdownload.plugin.api.work.query.TagOption;
import top.sywyar.pixivdownload.plugin.api.work.query.TagQuery;
import top.sywyar.pixivdownload.plugin.api.work.query.WorkQuery;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkRestriction;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkSummary;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link WorkQueryService} 的核心实现：插画侧代理 {@link GalleryRepository}（id 查询 /
 * 标签 / 作者目录 / 关联查询）与 {@link PixivDatabase}（三态判重）；小说侧代理
 * {@link NovelGalleryRepository}
 * （目录计数 / 关联查询）、{@link NovelMetadataRepository}（三态判重 / 行 / 系列池）与
 * {@link NovelWorkSearch}（只基于宿主窄投影做元数据过滤）。小说正文、FTS 与系列目录归小说
 * 插件；宿主查询只接受中性元数据条件。
 *
 * <p>插画侧与小说侧 SQL 仓库均已收编进核心数据层，不再反向 import gallery / novel.db。
 */
@Component
public class CoreWorkQueryService implements WorkQueryService {

    private final GalleryRepository galleryRepository;
    private final NovelGalleryRepository novelGalleryRepository;
    private final PixivDatabase pixivDatabase;
    private final NovelMetadataRepository novelMetadataRepository;
    private final AuthorService authorService;
    private final NovelWorkSearch novelWorkSearch;

    public CoreWorkQueryService(GalleryRepository galleryRepository,
                                NovelGalleryRepository novelGalleryRepository,
                                PixivDatabase pixivDatabase,
                                NovelMetadataRepository novelMetadataRepository,
                                AuthorService authorService) {
        this.galleryRepository = galleryRepository;
        this.novelGalleryRepository = novelGalleryRepository;
        this.pixivDatabase = pixivDatabase;
        this.novelMetadataRepository = novelMetadataRepository;
        this.authorService = authorService;
        this.novelWorkSearch = new NovelWorkSearch(novelMetadataRepository, novelGalleryRepository, authorService);
    }

    @Override
    public PagedResult<WorkSummary> search(WorkQuery query) {
        return switch (query.workType()) {
            case ARTWORK -> artworkSearch(query);
            case NOVEL -> novelSearch(query);
        };
    }

    @Override
    public List<WorkSummary> searchAll(WorkQuery query) {
        return switch (query.workType()) {
            case ARTWORK -> toSummaries(query.workType(),
                    galleryRepository.findAllArtworkIds(toGalleryQuery(query)));
            case NOVEL -> toSummaries(query.workType(),
                    novelWorkSearch.filteredIds(query, toGuestRestriction(query.restriction())));
        };
    }

    @Override
    public boolean hasWork(WorkType workType, long workId) {
        return switch (workType) {
            case ARTWORK -> pixivDatabase.hasArtwork(workId);
            case NOVEL -> novelMetadataRepository.hasNovel(workId);
        };
    }

    @Override
    public boolean hasActiveWork(WorkType workType, long workId) {
        return switch (workType) {
            case ARTWORK -> pixivDatabase.hasActiveArtwork(workId);
            case NOVEL -> novelMetadataRepository.hasActiveNovel(workId);
        };
    }

    @Override
    public List<WorkSummary> relatedByTags(WorkType workType, long workId, int limit) {
        return switch (workType) {
            case ARTWORK -> toSummaries(workType, galleryRepository.findRelatedByTags(workId, limit));
            case NOVEL -> toSummaries(workType, novelGalleryRepository.findRelatedByTags(workId, limit));
        };
    }

    @Override
    public List<WorkSummary> byAuthor(WorkType workType, long authorId, long excludeWorkId, int limit) {
        return switch (workType) {
            case ARTWORK -> toSummaries(workType,
                    galleryRepository.findByAuthor(authorId, excludeWorkId, limit));
            case NOVEL -> toSummaries(workType,
                    novelGalleryRepository.findNovelIdsByAuthor(authorId, excludeWorkId, limit));
        };
    }

    @Override
    public List<WorkSummary> bySeries(WorkType workType, long seriesId, long excludeWorkId, int limit) {
        return switch (workType) {
            case ARTWORK -> toSummaries(workType,
                    galleryRepository.findBySeries(seriesId, excludeWorkId, limit));
            case NOVEL -> toSummaries(workType,
                    novelGalleryRepository.findNovelIdsBySeries(seriesId, excludeWorkId, limit));
        };
    }

    @Override
    public Optional<SeriesNeighbors> seriesNeighbors(WorkType workType, long workId) {
        return switch (workType) {
            case ARTWORK -> artworkSeriesNeighbors(workId);
            case NOVEL -> novelSeriesNeighbors(workId);
        };
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
        return switch (workType) {
            case ARTWORK -> {
                GalleryRepository.TagOption tag = galleryRepository.findTagByExactName(name, translatedName);
                yield tag == null
                        ? Optional.empty()
                        : Optional.of(new TagOption(tag.tagId(), tag.name(), tag.translatedName(),
                                tag.artworkCount()));
            }
            case NOVEL -> {
                NovelTagOption tag = novelGalleryRepository.findTagByExactName(name, translatedName);
                yield tag == null
                        ? Optional.empty()
                        : Optional.of(new TagOption(tag.tagId(), tag.name(), tag.translatedName(),
                                tag.novelCount()));
            }
        };
    }

    @Override
    public List<AuthorSummary> authors(AuthorQuery query) {
        return switch (query.workType()) {
            case ARTWORK -> artworkAuthors(query);
            case NOVEL -> novelAuthors(query);
        };
    }

    @Override
    public Map<Long, String> authorNames(Collection<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(authorService.getAuthorNames(authorIds)));
    }

    @Override
    public Map<Long, Long> countBySeries(WorkType workType, WorkRestriction restriction) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        switch (workType) {
            case ARTWORK -> galleryRepository.findSeriesCounts(toGuestRestriction(restriction))
                    .forEach(row -> counts.put(row.seriesId(), row.artworkCount()));
            case NOVEL -> counts.putAll(novelGalleryRepository
                    .countVisibleNovelsBySeries(toGuestRestriction(restriction)));
        }
        return Collections.unmodifiableMap(counts);
    }

    private PagedResult<WorkSummary> artworkSearch(WorkQuery query) {
        GalleryRepository.QueryResult result = galleryRepository.findArtworkIds(toGalleryQuery(query));
        int totalPages = (int) Math.ceil((double) result.totalElements() / query.size());
        return new PagedResult<>(
                toSummaries(query.workType(), result.ids()),
                result.totalElements(),
                query.page(),
                query.size(),
                totalPages);
    }

    /** 小说列表：内存过滤取全量命中 id 后在本侧完成分页数学（与原小说画廊实现一致）。 */
    private PagedResult<WorkSummary> novelSearch(WorkQuery query) {
        List<Long> ids = novelWorkSearch.filteredIds(query, toGuestRestriction(query.restriction()));
        int total = ids.size();
        int from = Math.max(0, query.page() * query.size());
        int to = Math.min(from + query.size(), total);
        List<Long> pageIds = from >= total ? List.of() : ids.subList(from, to);
        int totalPages = (int) Math.ceil((double) total / Math.max(1, query.size()));
        return new PagedResult<>(
                toSummaries(query.workType(), pageIds),
                total,
                query.page(),
                query.size(),
                totalPages);
    }

    private Optional<SeriesNeighbors> artworkSeriesNeighbors(long workId) {
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

    /**
     * 小说系列相邻导航：在同系列已下载小说中按 series_order 找最近的上一章 / 下一章
     * （不要求严格相邻），自小说画廊服务下沉。按接口契约，基准行软删除或无序号时返回 empty。
     */
    private Optional<SeriesNeighbors> novelSeriesNeighbors(long workId) {
        NovelMetadataRow current = novelMetadataRepository.getNovel(workId);
        if (current == null || current.deleted() || current.seriesId() == null || current.seriesId() <= 0
                || current.seriesOrder() == null) {
            return Optional.empty();
        }
        long currentOrder = current.seriesOrder();
        NovelMetadataRow prev = null;
        NovelMetadataRow next = null;
        for (NovelMetadataRow r : novelMetadataRepository.getNovelsBySeriesId(current.seriesId())) {
            long ord = r.seriesOrder() == null ? -1 : r.seriesOrder();
            if (ord < currentOrder && (prev == null
                    || (prev.seriesOrder() != null && ord > prev.seriesOrder()))) {
                prev = r;
            }
            if (ord > currentOrder && (next == null
                    || (next.seriesOrder() != null && ord < next.seriesOrder()))) {
                next = r;
            }
        }
        NovelSeriesTitleRow series = novelMetadataRepository.getSeries(current.seriesId());
        return Optional.of(new SeriesNeighbors(
                current.seriesId(),
                series == null ? null : series.title(),
                currentOrder,
                toNovelNeighbor(prev),
                toNovelNeighbor(next)));
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

    /** 小说作者目录：可见作品计数（restriction 为 null 时统计全部未删行）+ 作者池补名。 */
    private List<AuthorSummary> novelAuthors(AuthorQuery query) {
        List<NovelAuthorSummary> counts = novelGalleryRepository
                .findVisibleNovelAuthorCounts(toGuestRestriction(query.restriction()));
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

    /** 小说标签目录：可见作品计数（restriction 为 null 时统计全部未删行），钳制收在仓库侧。 */
    private List<TagOption> novelTags(TagQuery query) {
        List<NovelTagOption> tags = novelGalleryRepository.findVisibleNovelTagCounts(
                toGuestRestriction(query.restriction()), query.search(), query.limit());
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

    private static SeriesNeighbors.Neighbor toNovelNeighbor(NovelMetadataRow record) {
        if (record == null) {
            return null;
        }
        return new SeriesNeighbors.Neighbor(record.novelId(), record.title(),
                record.seriesOrder() == null ? 0 : record.seriesOrder());
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
}
