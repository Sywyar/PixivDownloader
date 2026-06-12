package top.sywyar.pixivdownload.plugin.api;

import java.util.List;

/**
 * 系列目录行：系列与其可见作品数。
 *
 * <p>{@code coverExt} / {@code tags} 是系列卡片的展示装饰列：小说系列目录由实现批量补全；
 * 插画系列目录今天没有对应数据，恒为 {@code null} / 空列表——这是「没有数据」的合法取值，
 * 区别于尚未接入实现时的 {@link UnsupportedOperationException}。
 *
 * @param seriesId   系列 id
 * @param title      系列标题；系列池缺行时实现以 {@code String.valueOf(seriesId)} 兜底
 * @param authorId   系列作者 id，可为 {@code null}
 * @param authorName 系列作者名，作者池缺名时为 {@code null}
 * @param workCount  该系列的可见作品数（按查询条件统计）
 * @param coverExt   系列封面扩展名，无封面 / 无数据时为 {@code null}
 * @param tags       系列标签（防御性拷贝，不可变），无数据时为空列表
 */
public record SeriesSummary(
        long seriesId,
        String title,
        Long authorId,
        String authorName,
        long workCount,
        String coverExt,
        List<WorkTag> tags) {

    public SeriesSummary {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** 无装饰列的便捷构造（插画系列目录等场景）。 */
    public SeriesSummary(long seriesId, String title, Long authorId, String authorName, long workCount) {
        this(seriesId, title, authorId, authorName, workCount, null, List.of());
    }
}
