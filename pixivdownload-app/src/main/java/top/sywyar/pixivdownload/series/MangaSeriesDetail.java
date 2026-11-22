package top.sywyar.pixivdownload.series;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

public record MangaSeriesDetail(
        long seriesId,
        String title,
        @HttpApiNullable Long authorId,
        @HttpApiNullable String authorName,
        long artworkCount,
        Long updatedTime,
        @HttpApiNullable String description,
        @HttpApiNullable String coverExt,
        @HttpApiNullable String coverFolder
) {}
