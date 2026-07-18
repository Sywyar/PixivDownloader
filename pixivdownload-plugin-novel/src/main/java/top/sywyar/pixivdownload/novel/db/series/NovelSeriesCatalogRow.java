package top.sywyar.pixivdownload.novel.db.series;

/** 小说插件拥有的系列目录持久化投影。 */
public record NovelSeriesCatalogRow(
        long seriesId,
        String title,
        Long authorId,
        long novelCount,
        String coverExt
) {
}
