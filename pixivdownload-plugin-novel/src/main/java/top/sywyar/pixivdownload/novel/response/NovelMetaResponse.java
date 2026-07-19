package top.sywyar.pixivdownload.novel.response;

import top.sywyar.pixivdownload.core.work.model.WorkTag;

import java.util.List;
import java.util.Map;

public record NovelMetaResponse(
        long novelId,
        String title,
        int xRestrict,
        boolean isAi,
        int bookmarkCount,
        Long authorId,
        String authorName,
        String description,
        List<WorkTag> tags,
        Long seriesId,
        Long seriesOrder,
        String seriesTitle,
        String content,
        Integer wordCount,
        Integer textLength,
        Integer readingTimeSeconds,
        Integer pageCount,
        boolean isOriginal,
        String language,
        String coverUrl,
        Long uploadTimestamp,
        /** [uploadedimage:id] → 原图 URL（pximg.net）。可能为空。 */
        Map<String, String> textEmbeddedImages
) {}
