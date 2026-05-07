package top.sywyar.pixivdownload.novel.db;

public record NovelTagOption(
        long tagId,
        String name,
        String translatedName,
        long novelCount
) {}
