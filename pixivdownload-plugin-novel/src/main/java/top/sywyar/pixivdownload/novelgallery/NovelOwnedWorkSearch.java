package top.sywyar.pixivdownload.novelgallery;

import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.plugin.api.work.model.PagedResult;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkSummary;
import top.sywyar.pixivdownload.plugin.api.work.query.WorkQuery;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 小说插件拥有的查询补充层：传给宿主的 {@link WorkQuery} 只包含中性元数据条件；正文检索词作为
 * 插件私有参数在 {@link NovelDatabase} 内查询 {@code raw_content} / FTS，再按宿主结果顺序求交集。
 */
public final class NovelOwnedWorkSearch {

    private final WorkQueryService workQueryService;
    private final NovelDatabase novelDatabase;

    public NovelOwnedWorkSearch(WorkQueryService workQueryService, NovelDatabase novelDatabase) {
        this.workQueryService = Objects.requireNonNull(workQueryService, "workQueryService");
        this.novelDatabase = Objects.requireNonNull(novelDatabase, "novelDatabase");
    }

    public PagedResult<WorkSummary> search(WorkQuery query, String contentQuery) {
        Objects.requireNonNull(query, "query");
        if (contentQuery == null || contentQuery.isBlank()) {
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
        if (contentQuery == null || contentQuery.isBlank()) {
            return workQueryService.searchAll(query);
        }
        Set<Long> contentMatches = novelDatabase.searchNovelContentIds(contentQuery);
        if (contentMatches.isEmpty()) {
            return List.of();
        }
        return workQueryService.searchAll(query).stream()
                .filter(summary -> contentMatches.contains(summary.workId()))
                .toList();
    }
}
