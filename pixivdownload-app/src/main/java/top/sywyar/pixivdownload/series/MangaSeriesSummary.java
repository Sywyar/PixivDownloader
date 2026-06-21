package top.sywyar.pixivdownload.series;

public record MangaSeriesSummary(
        long seriesId,
        String title,
        Long authorId,
        String authorName,
        long artworkCount
) {}
