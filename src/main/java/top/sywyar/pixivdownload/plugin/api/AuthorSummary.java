package top.sywyar.pixivdownload.plugin.api;

/**
 * 作者目录行：作者与其可见作品数。
 *
 * @param authorId  作者 id
 * @param name      作者名；作者池缺名时实现以 {@code String.valueOf(authorId)} 兜底
 * @param workCount 该作者的可见作品数（按查询条件统计）
 */
public record AuthorSummary(long authorId, String name, long workCount) {
}
