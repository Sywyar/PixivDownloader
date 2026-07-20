package top.sywyar.pixivdownload.download.response;

import top.sywyar.pixivdownload.core.db.TagDto;

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
        List<TagDto> tags,
        Long seriesId,
        Long seriesOrder,
        String seriesTitle
) {}
