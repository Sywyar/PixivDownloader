package top.sywyar.pixivdownload.plugin.api;

/**
 * 系列目录行：系列与其可见作品数。
 *
 * @param seriesId   系列 id
 * @param title      系列标题；系列池缺行时实现以 {@code String.valueOf(seriesId)} 兜底
 * @param authorId   系列作者 id，可为 {@code null}
 * @param authorName 系列作者名，作者池缺名时为 {@code null}
 * @param workCount  该系列的可见作品数（按查询条件统计）
 */
public record SeriesSummary(long seriesId, String title, Long authorId, String authorName, long workCount) {
}
