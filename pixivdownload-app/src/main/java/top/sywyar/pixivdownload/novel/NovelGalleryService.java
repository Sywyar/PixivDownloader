package top.sywyar.pixivdownload.novel;

import lombok.RequiredArgsConstructor;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.core.metadata.GuestRestriction;
import top.sywyar.pixivdownload.core.metadata.novel.NovelAuthorSummary;
import top.sywyar.pixivdownload.core.metadata.novel.NovelSeriesSummary;
import top.sywyar.pixivdownload.core.metadata.novel.NovelTagOption;
import top.sywyar.pixivdownload.plugin.api.work.query.AuthorQuery;
import top.sywyar.pixivdownload.plugin.api.work.model.NovelWorkDetails;
import top.sywyar.pixivdownload.plugin.api.work.model.PagedResult;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.work.query.SeriesNeighbors;
import top.sywyar.pixivdownload.plugin.api.work.query.SeriesQuery;
import top.sywyar.pixivdownload.plugin.api.work.query.TagOption;
import top.sywyar.pixivdownload.plugin.api.work.query.TagQuery;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkDeletionService;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.work.query.WorkQuery;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkRestriction;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkSummary;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkTag;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 小说画廊页面服务：列表 / 详情 / 系列 / 目录查询统一走核心接口——{@link WorkQueryService}
 * 取 id 分页与目录聚合、{@link WorkMetadataRepository} 批量补全行数据（search → hydrate 两步）；
 * 删除链路委托核心统一删除入口 {@link WorkDeletionService#delete} / {@link WorkDeletionService#deleteAll}
 *（判存 → 删文件 → 软删 DB 的编排封装在核心实现），不再直接依赖底层数据库与作者池实现。
 */
@PluginManagedBean
@RequiredArgsConstructor
public class NovelGalleryService {

    private final WorkQueryService workQueryService;
    private final WorkMetadataRepository workMetadataRepository;
    private final WorkDeletionService workDeletionService;

    public PagedNovels query(NovelGalleryQuery q) {
        PagedResult<WorkSummary> result = workQueryService.search(toWorkQuery(q));
        List<NovelView> content = toViews(toIds(result.content()));
        return new PagedNovels(content, (int) result.totalElements(),
                result.page(), result.size(), result.totalPages());
    }

    public List<Long> findNovelIds(NovelGalleryQuery q) {
        return toIds(workQueryService.searchAll(toWorkQuery(q)));
    }

    public NovelView find(long novelId) {
        return workMetadataRepository.find(WorkType.NOVEL, novelId)
                .map(NovelGalleryService::toView)
                .orElse(null);
    }

    /**
     * 删除单本小说：委托核心统一删除入口 {@link WorkDeletionService#delete}——判存 → 删磁盘文件
     *（正文 TXT/HTML/EPUB、封面、内嵌图、独占目录）→ 清理 DB 派生数据并软删主行的编排封装在核心实现，
     * 使下载判重能识别「已下载过，但被删除」、避免被当作未下载重新下载。系列封面与合订文件属于系列、
     * 不在此删除。小说不存在或已被标记删除时返回 {@code false}；磁盘文件删除失败（被锁定 / 权限不足等）
     * 抛出 409、不触碰数据库。
     */
    public boolean deleteNovel(long novelId) {
        return workDeletionService.delete(WorkType.NOVEL, novelId);
    }

    /** 批量删除小说，返回实际删除的数量。 */
    public int deleteNovels(Collection<Long> novelIds) {
        return workDeletionService.deleteAll(WorkType.NOVEL, novelIds);
    }

    public List<NovelView> bySeries(long seriesId, int limit) {
        if (seriesId <= 0 || limit <= 0) return List.of();
        return toViews(toIds(workQueryService.bySeries(WorkType.NOVEL, seriesId, 0L, limit)));
    }

    /** 系列内相邻章节导航；小说不存在、无系列或无序号时返回 {@code null}。 */
    public SeriesNeighbors seriesNeighbors(long novelId) {
        return workQueryService.seriesNeighbors(WorkType.NOVEL, novelId).orElse(null);
    }

    public PagedAuthors getPagedAuthorsWithNovels(int page, int size, String search, String sort) {
        return getPagedAuthorsWithNovels(page, size, search, sort, null);
    }

    public PagedAuthors getPagedAuthorsWithNovels(int page, int size, String search, String sort,
                                                  GuestInviteSession session) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<NovelAuthorSummary> rows = workQueryService
                .authors(new AuthorQuery(WorkType.NOVEL, toWorkRestriction(session)))
                .stream()
                .map(item -> new NovelAuthorSummary(item.authorId(), item.name(), item.workCount()))
                .filter(item -> term.isEmpty()
                        || String.valueOf(item.authorId()).contains(term)
                        || (item.name() != null && item.name().toLowerCase(Locale.ROOT).contains(term)))
                .sorted(authorSummaryComparator(sort))
                .toList();
        return pageAuthors(rows, safePage, safeSize);
    }

    public PagedSeries getPagedSeriesWithNovels(int page, int size, String search, String sort) {
        return getPagedSeriesWithNovels(page, size, search, sort, null);
    }

    public PagedSeries getPagedSeriesWithNovels(int page, int size, String search, String sort,
                                                GuestInviteSession session) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<NovelSeriesSummary> rows = workQueryService
                .series(new SeriesQuery(WorkType.NOVEL, toWorkRestriction(session)))
                .stream()
                .map(item -> new NovelSeriesSummary(item.seriesId(), item.title(), item.authorId(),
                        item.authorName(), item.workCount(), item.coverExt(), toTagDtos(item.tags())))
                .filter(item -> term.isEmpty()
                        || String.valueOf(item.seriesId()).contains(term)
                        || (item.title() != null && item.title().toLowerCase(Locale.ROOT).contains(term))
                        || (item.authorName() != null && item.authorName().toLowerCase(Locale.ROOT).contains(term)))
                .sorted(seriesSummaryComparator(sort))
                .toList();
        return pageSeries(rows, safePage, safeSize);
    }

    public List<NovelTagOption> listTags(String search, int limit) {
        return listTags(search, limit, null);
    }

    public List<NovelTagOption> listTags(String search, int limit, GuestInviteSession session) {
        List<TagOption> tags = workQueryService.tags(
                new TagQuery(WorkType.NOVEL, search, limit, toWorkRestriction(session)));
        List<NovelTagOption> out = new ArrayList<>(tags.size());
        for (TagOption tag : tags) {
            out.add(new NovelTagOption(tag.tagId(), tag.name(), tag.translatedName(), tag.workCount()));
        }
        return out;
    }

    private List<NovelView> toViews(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<WorkMetadata> metas = workMetadataRepository.findAll(WorkType.NOVEL, ids);
        List<NovelView> out = new ArrayList<>(metas.size());
        for (WorkMetadata meta : metas) {
            out.add(toView(meta));
        }
        return out;
    }

    private static NovelView toView(WorkMetadata meta) {
        NovelWorkDetails details = meta.novel();
        return new NovelView(
                meta.workId(),
                meta.title(),
                meta.folder(),
                meta.extensions(),
                meta.downloadTime(),
                meta.xRestrict(),
                meta.isAi(),
                meta.authorId(),
                meta.authorName(),
                meta.description(),
                meta.seriesId(),
                meta.seriesOrder(),
                details.wordCount(),
                details.textLength(),
                details.readingTimeSeconds(),
                details.pageCount(),
                details.isOriginal(),
                details.xLanguage(),
                toTagDtos(meta.tags()),
                details.coverExt(),
                details.embeddedImageIds(),
                details.translatedLanguages()
        );
    }

    private static List<Long> toIds(List<WorkSummary> summaries) {
        List<Long> out = new ArrayList<>(summaries.size());
        for (WorkSummary summary : summaries) {
            out.add(summary.workId());
        }
        return out;
    }

    private static List<TagDto> toTagDtos(List<WorkTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<TagDto> out = new ArrayList<>(tags.size());
        for (WorkTag tag : tags) {
            out.add(new TagDto(tag.tagId(), tag.name(), tag.translatedName()));
        }
        return out;
    }

    private static WorkQuery toWorkQuery(NovelGalleryQuery q) {
        return WorkQuery.builder(WorkType.NOVEL)
                .page(q.page())
                .size(q.size())
                .sort(q.sort())
                .order(q.order())
                .search(q.search())
                .searchType(q.searchType())
                .r18(q.r18())
                .ai(q.ai())
                .collectionIds(toList(q.collectionIds()))
                .tagIds(toList(q.tagIds()))
                .excludedTagIds(toList(q.notTagIds()))
                .optionalTagIds(toList(q.orTagIds()))
                .authorIds(toList(q.authorIds()))
                .excludedAuthorIds(toList(q.notAuthorIds()))
                .optionalAuthorIds(toList(q.orAuthorIds()))
                .seriesIds(toList(q.seriesIds()))
                .excludedSeriesIds(toList(q.notSeriesIds()))
                .restriction(toWorkRestriction(q.guestSession()))
                .build();
    }

    private static List<Long> toList(Set<Long> ids) {
        return ids == null ? null : new ArrayList<>(ids);
    }

    private static WorkRestriction toWorkRestriction(GuestInviteSession session) {
        GuestRestriction restriction = GuestRestriction.forNovel(session);
        if (restriction == null) {
            return null;
        }
        return new WorkRestriction(
                restriction.allowedXRestricts(),
                restriction.tagUnrestricted(),
                restriction.tagIds(),
                restriction.authorUnrestricted(),
                restriction.authorIds());
    }

    private Comparator<NovelAuthorSummary> authorSummaryComparator(String sort) {
        Comparator<NovelAuthorSummary> comparator = switch (sort == null ? "name" : sort) {
            case "novels" -> Comparator.comparingLong(NovelAuthorSummary::novelCount).reversed();
            case "authorId" -> Comparator.comparingLong(NovelAuthorSummary::authorId);
            default -> Comparator.comparing(item -> item.name() == null
                    ? "" : item.name().toLowerCase(Locale.ROOT));
        };
        return comparator.thenComparingLong(NovelAuthorSummary::authorId);
    }

    private PagedAuthors pageAuthors(List<NovelAuthorSummary> rows, int page, int size) {
        int total = rows.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        int totalPages = (int) Math.ceil((double) total / size);
        return new PagedAuthors(rows.subList(from, to), total, page, size, totalPages);
    }

    private Comparator<NovelSeriesSummary> seriesSummaryComparator(String sort) {
        Comparator<NovelSeriesSummary> comparator = switch (sort == null ? "title" : sort) {
            case "novels" -> Comparator.comparingLong(NovelSeriesSummary::novelCount).reversed();
            case "seriesId" -> Comparator.comparingLong(NovelSeriesSummary::seriesId);
            default -> Comparator.comparing(item -> item.title() == null
                    ? "" : item.title().toLowerCase(Locale.ROOT));
        };
        return comparator.thenComparingLong(NovelSeriesSummary::seriesId);
    }

    private PagedSeries pageSeries(List<NovelSeriesSummary> rows, int page, int size) {
        int total = rows.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        int totalPages = (int) Math.ceil((double) total / size);
        return new PagedSeries(rows.subList(from, to), total, page, size, totalPages);
    }

    public record PagedAuthors(List<NovelAuthorSummary> content, long totalElements,
                               int page, int size, int totalPages) {}

    public record PagedSeries(List<NovelSeriesSummary> content, long totalElements,
                              int page, int size, int totalPages) {}

    /** 合法搜索范围，与插画画廊一致。 */
    public static final Set<String> ALLOWED_SEARCH_TYPES = Set.of(
            "all", "title", "author", "id", "authorId", "desc", "tag", "tagExact", "content");

    public static String normalizeSearchType(String value) {
        if (value == null) return "all";
        String trimmed = value.trim();
        return ALLOWED_SEARCH_TYPES.contains(trimmed) ? trimmed : "all";
    }

    public record NovelGalleryQuery(int page, int size, String sort, String order,
                                    String search, String searchType, String r18, String ai,
                                    Set<Long> collectionIds,
                                    Set<Long> tagIds, Set<Long> notTagIds, Set<Long> orTagIds,
                                    Set<Long> authorIds, Set<Long> notAuthorIds, Set<Long> orAuthorIds,
                                    Set<Long> seriesIds, Set<Long> notSeriesIds,
                                    GuestInviteSession guestSession) {
        public NovelGalleryQuery(int page, int size, String sort, String order,
                                 String search, String searchType, String r18, String ai,
                                 Set<Long> collectionIds,
                                 Set<Long> tagIds, Set<Long> notTagIds, Set<Long> orTagIds,
                                 Set<Long> authorIds, Set<Long> notAuthorIds, Set<Long> orAuthorIds,
                                 Set<Long> seriesIds, Set<Long> notSeriesIds) {
            this(page, size, sort, order, search, searchType, r18, ai, collectionIds,
                    tagIds, notTagIds, orTagIds, authorIds, notAuthorIds, orAuthorIds,
                    seriesIds, notSeriesIds, null);
        }

        public NovelGalleryQuery(int page, int size, String sort, String order,
                                 String search, String r18, String ai) {
            this(page, size, sort, order, search, "all", r18, ai, null,
                    null, null, null, null, null, null, null, null, null);
        }
        public NovelGalleryQuery(int page, int size, String sort, String order,
                                 String search, String r18, String ai,
                                 Set<Long> collectionIds) {
            this(page, size, sort, order, search, "all", r18, ai, collectionIds,
                    null, null, null, null, null, null, null, null, null);
        }
    }

    public record PagedNovels(List<NovelView> content, int totalElements, int page, int size,
                              int totalPages) {}

    public record NovelView(
            long novelId,
            String title,
            String folder,
            String extensions,
            long time,
            Integer xRestrict,
            Boolean isAi,
            Long authorId,
            String authorName,
            String description,
            Long seriesId,
            Long seriesOrder,
            Integer wordCount,
            Integer textLength,
            Integer readingTimeSeconds,
            Integer pageCount,
            Boolean isOriginal,
            String xLanguage,
            List<TagDto> tags,
            String coverExt,
            List<String> embeddedImageIds,
            List<String> translatedLanguages
    ) {}

}
