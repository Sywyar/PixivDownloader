package top.sywyar.pixivdownload.novel.db;

import top.sywyar.pixivdownload.download.db.TagDto;

import java.util.List;

public record NovelSeriesSummary(
        long seriesId,
        String title,
        Long authorId,
        String authorName,
        long novelCount,
        String coverExt,
        List<TagDto> tags
) {
    public NovelSeriesSummary(long seriesId, String title, Long authorId,
                              String authorName, long novelCount) {
        this(seriesId, title, authorId, authorName, novelCount, null, List.of());
    }
}
