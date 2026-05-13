package top.sywyar.pixivdownload.novel.db;

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
