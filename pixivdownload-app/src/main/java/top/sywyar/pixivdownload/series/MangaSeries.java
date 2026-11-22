package top.sywyar.pixivdownload.series;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

public record MangaSeries(
        long seriesId,
        String title,
        @HttpApiNullable Long authorId,
        long updatedTime,
        @HttpApiNullable String description,
        @HttpApiNullable String coverExt,
        @HttpApiNullable String coverFolder
) {}
