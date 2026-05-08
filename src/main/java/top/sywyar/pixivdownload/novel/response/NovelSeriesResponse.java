package top.sywyar.pixivdownload.novel.response;

import top.sywyar.pixivdownload.download.db.TagDto;

import java.util.List;

public record NovelSeriesResponse(
        NovelSeriesMeta series,
        List<NovelSeriesItem> items,
        int page,
        boolean isLastPage
) {
    public record NovelSeriesMeta(
            long seriesId,
            String title,
            Long authorId,
            String authorName,
            int total,
            String language,
            boolean isOriginal,
            int totalCharacterCount,
            int totalWordCount
    ) {}

    public record NovelSeriesItem(
            String id,
            String title,
            int xRestrict,
            int aiType,
            int wordCount,
            int textLength,
            Integer readingTimeSeconds,
            String userId,
            String userName,
            int seriesOrder,
            String coverUrl,
            Long uploadTimestamp,
            List<TagDto> tags
    ) {}
}
