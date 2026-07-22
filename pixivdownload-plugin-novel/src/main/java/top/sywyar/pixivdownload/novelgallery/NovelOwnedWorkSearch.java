package top.sywyar.pixivdownload.novelgallery;

import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.metadata.NovelWorkDetailsRepository;
import top.sywyar.pixivdownload.core.work.model.PagedResult;
import top.sywyar.pixivdownload.core.work.model.WorkSummary;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 小说插件拥有的查询补充层：传给宿主的 {@link WorkQuery} 只包含中性元数据条件；正文检索词作为
 * 插件私有参数在 {@link NovelDatabase} 内查询 {@code raw_content} / FTS，再按宿主结果顺序求交集。
 */
public final class NovelOwnedWorkSearch {

    private final WorkQueryService workQueryService;
    private final NovelDatabase novelDatabase;
    private final NovelWorkDetailsRepository novelWorkDetailsRepository;

    public NovelOwnedWorkSearch(WorkQueryService workQueryService,
                                NovelDatabase novelDatabase,
                                NovelWorkDetailsRepository novelWorkDetailsRepository) {
        this.workQueryService = Objects.requireNonNull(workQueryService, "workQueryService");
        this.novelDatabase = Objects.requireNonNull(novelDatabase, "novelDatabase");
        this.novelWorkDetailsRepository = Objects.requireNonNull(
                novelWorkDetailsRepository, "novelWorkDetailsRepository");
    }

    public PagedResult<WorkSummary> search(WorkQuery query, String contentQuery) {
        Objects.requireNonNull(query, "query");
        if (!hasPrivateContentQuery(contentQuery) && !sortsByWordCount(query)) {
            return workQueryService.search(query);
        }
        List<WorkSummary> filtered = searchAll(query, contentQuery);
        int page = Math.max(0, query.page());
        int size = Math.max(1, query.size());
        long requestedFrom = (long) page * size;
        int from = (int) Math.min(requestedFrom, filtered.size());
        int to = (int) Math.min((long) from + size, filtered.size());
        int totalPages = (int) Math.ceil((double) filtered.size() / size);
        return new PagedResult<>(filtered.subList(from, to), filtered.size(), page, size, totalPages);
    }

    public List<WorkSummary> searchAll(WorkQuery query, String contentQuery) {
        Objects.requireNonNull(query, "query");
        boolean sortByWordCount = sortsByWordCount(query);
        boolean filterByContent = hasPrivateContentQuery(contentQuery);
        if (!filterByContent && !sortByWordCount) {
            return workQueryService.searchAll(query);
        }

        Set<Long> contentMatches = filterByContent
                ? novelDatabase.searchNovelContentIds(contentQuery)
                : Set.of();
        if (filterByContent && contentMatches.isEmpty()) {
            return List.of();
        }

        WorkQuery hostQuery = sortByWordCount ? withNeutralDateSort(query) : query;
        List<WorkSummary> filtered = new ArrayList<>(workQueryService.searchAll(hostQuery));
        if (filterByContent) {
            filtered.removeIf(summary -> !contentMatches.contains(summary.workId()));
        }
        if (sortByWordCount && filtered.size() > 1) {
            Map<Long, Integer> wordCounts = novelWorkDetailsRepository.findWordCounts(
                    filtered.stream().map(WorkSummary::workId).toList());
            Comparator<WorkSummary> comparator = Comparator.comparingInt(summary -> {
                Integer count = wordCounts.get(summary.workId());
                return count == null ? 0 : count;
            });
            if (!"asc".equalsIgnoreCase(query.order())) {
                comparator = comparator.reversed();
            }
            filtered.sort(comparator);
        }
        return List.copyOf(filtered);
    }

    private static boolean hasPrivateContentQuery(String contentQuery) {
        return contentQuery != null && !contentQuery.isBlank();
    }

    private static boolean sortsByWordCount(WorkQuery query) {
        return "wordCount".equals(query.sort());
    }

    /** 字数相同的稳定顺序沿用原宿主实现的下载时间倒序基线。 */
    private static WorkQuery withNeutralDateSort(WorkQuery query) {
        return WorkQuery.builder(query.workType())
                .page(query.page())
                .size(query.size())
                .sort("date")
                .order("desc")
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
                .restriction(query.restriction())
                .build();
    }
}
