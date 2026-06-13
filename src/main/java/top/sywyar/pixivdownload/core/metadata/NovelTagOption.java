package top.sywyar.pixivdownload.core.metadata;

public record NovelTagOption(
        long tagId,
        String name,
        String translatedName,
        long novelCount
) {}
