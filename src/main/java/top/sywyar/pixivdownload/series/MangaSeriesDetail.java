package top.sywyar.pixivdownload.series;

public record MangaSeriesDetail(
        long seriesId,
        String title,
        Long authorId,
        String authorName,
        long artworkCount,
        Long updatedTime,
        String description,
        String coverExt,
        String coverFolder
) {}
