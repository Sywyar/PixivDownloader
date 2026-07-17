package top.sywyar.pixivdownload.core.metadata.novel;

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
