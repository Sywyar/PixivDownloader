package top.sywyar.pixivdownload.series;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

public record MangaSeriesSummary(
        long seriesId,
        String title,
        @HttpApiNullable Long authorId,
        @HttpApiNullable String authorName,
        long artworkCount
) {}
