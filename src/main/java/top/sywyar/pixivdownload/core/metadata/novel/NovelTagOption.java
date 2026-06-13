package top.sywyar.pixivdownload.core.metadata.novel;

public record NovelTagOption(
        long tagId,
        String name,
        String translatedName,
        long novelCount
) {}
