package top.sywyar.pixivdownload.novel.db;

public record NovelAuthorSummary(
        long authorId,
        String name,
        long novelCount
) {}
