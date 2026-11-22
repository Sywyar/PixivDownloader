package top.sywyar.pixivdownload.download.response.artwork;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

import top.sywyar.pixivdownload.core.work.model.WorkTag;

import java.util.List;

public record ArtworkMetaResponse(
        int illustType,
        String illustTitle,
        int xRestrict,
        boolean isAi,
        int bookmarkCount,
        int pageCount,
        Long authorId,
        String authorName,
        String description,
        List<WorkTag> tags,
        @HttpApiNullable Long seriesId,
        @HttpApiNullable Long seriesOrder,
        @HttpApiNullable String seriesTitle,
        String rawMetaJson
) {}
