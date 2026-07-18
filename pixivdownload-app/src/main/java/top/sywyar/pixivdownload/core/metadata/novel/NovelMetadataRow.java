package top.sywyar.pixivdownload.core.metadata.novel;

/**
 * 宿主核心读取小说事实时使用的窄投影。
 *
 * <p>正文 {@code raw_content} 由小说插件拥有，不进入宿主查询行；宿主只读取跨类型检索、
 * 本地资产、可见性与 sidecar 投影确实需要的列。
 */
public record NovelMetadataRow(
        long novelId,
        String title,
        String folder,
        int count,
        String extensions,
        long time,
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
        String coverExt,
        boolean deleted,
        Long uploadTime
) {
    public NovelMetadataRow(long novelId,
                            String title,
                            String folder,
                            int count,
                            String extensions,
                            long time,
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
                            String coverExt) {
        this(novelId, title, folder, count, extensions, time, xRestrict, isAi, authorId, description,
                fileName, fileAuthorNameId, seriesId, seriesOrder, wordCount, textLength,
                readingTimeSeconds, pageCount, isOriginal, xLanguage, coverExt, false, null);
    }
}
