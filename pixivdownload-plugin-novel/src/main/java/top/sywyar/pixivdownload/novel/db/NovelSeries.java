package top.sywyar.pixivdownload.novel.db;

/** 小说插件持久化层的完整 {@code novel_series} 行。 */
public record NovelSeries(
        long seriesId,
        String title,
        Long authorId,
        long updatedTime,
        String description,
        String coverExt,
        String coverFolder
) {
}
