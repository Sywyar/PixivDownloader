package top.sywyar.pixivdownload.novel.db;

public record NovelSeriesSummary(
        long seriesId,
        String title,
        Long authorId,
        String authorName,
        long novelCount
) {}
