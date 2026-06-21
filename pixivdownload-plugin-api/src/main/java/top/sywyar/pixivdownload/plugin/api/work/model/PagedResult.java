package top.sywyar.pixivdownload.plugin.api.work.model;

import java.util.List;

/**
 * 分页查询结果。分页数学（总数统计、{@code totalPages} 计算）只在查询侧完成，
 * 内容补全（hydrate）一侧不得改变总数与顺序。
 *
 * @param content       当前页内容（防御性拷贝，不可变）
 * @param totalElements 过滤条件命中的总行数（跨全部页）
 * @param page          当前页号（0 起）
 * @param size          页大小
 * @param totalPages    总页数（{@code ceil(totalElements / size)}）
 */
public record PagedResult<T>(
        List<T> content,
        long totalElements,
        int page,
        int size,
        int totalPages) {

    public PagedResult {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
