package top.sywyar.pixivdownload.novelgallery;

import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.plugin.api.work.model.PagedResult;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkSummary;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.query.WorkQuery;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 小说插件拥有的查询补充层：通用元数据筛选 / 排序仍委托宿主 {@link WorkQueryService}，只有需要读取
 * {@code raw_content} / FTS 的正文搜索在插件私有 {@link NovelDatabase} 内完成，再按宿主结果顺序求交集。
 */
public final class NovelOwnedWorkSearch {

    private static final String CONTENT = "content";

    private final WorkQueryService workQueryService;
    private final NovelDatabase novelDatabase;

    public NovelOwnedWorkSearch(WorkQueryService workQueryService, NovelDatabase novelDatabase) {
        this.workQueryService = Objects.requireNonNull(workQueryService, "workQueryService");
        this.novelDatabase = Objects.requireNonNull(novelDatabase, "novelDatabase");
    }

    public PagedResult<WorkSummary> search(WorkQuery query) {
        Objects.requireNonNull(query, "query");
        if (!isContentSearch(query)) {
            return workQueryService.search(query);
        }
        List<WorkSummary> filtered = searchAll(query);
        int page = Math.max(0, query.page());
        int size = Math.max(1, query.size());
        long requestedFrom = (long) page * size;
        int from = (int) Math.min(requestedFrom, filtered.size());
        int to = (int) Math.min((long) from + size, filtered.size());
        int totalPages = (int) Math.ceil((double) filtered.size() / size);
        return new PagedResult<>(filtered.subList(from, to), filtered.size(), page, size, totalPages);
    }

    public List<WorkSummary> searchAll(WorkQuery query) {
        Objects.requireNonNull(query, "query");
        if (!isContentSearch(query)) {
            return workQueryService.searchAll(query);
        }
        Set<Long> contentMatches = novelDatabase.searchNovelContentIds(query.search());
        if (contentMatches.isEmpty()) {
            return List.of();
        }
        return workQueryService.searchAll(withoutPrivateContentSearch(query)).stream()
                .filter(summary -> contentMatches.contains(summary.workId()))
                .toList();
    }

    private static boolean isContentSearch(WorkQuery query) {
        return query.workType() == WorkType.NOVEL
                && CONTENT.equals(query.searchType())
                && query.search() != null
                && !query.search().isBlank();
    }

    private static WorkQuery withoutPrivateContentSearch(WorkQuery query) {
        return WorkQuery.builder(query.workType())
                .page(query.page())
                .size(query.size())
                .sort(query.sort())
                .order(query.order())
                .search(null)
                .searchType("all")
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
