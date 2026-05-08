package top.sywyar.pixivdownload.novel.db;

public record NovelRecord(
        long novelId,
        String title,
        String folder,
        int count,
        String extensions,
        long time,
        boolean moved,
        String moveFolder,
        Long moveTime,
        Integer xRestrict,
        Boolean isAi,
        Long authorId,
        String description,
        Long fileName,
        Long fileAuthorNameId,
        Long seriesId,
        Long seriesOrder,
        Integer wordCount,
        Integer textLength,
        Integer readingTimeSeconds,
        Integer pageCount,
        Boolean isOriginal,
        String xLanguage,
        String rawContent,
        String coverExt
) {
}
