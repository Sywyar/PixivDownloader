package top.sywyar.pixivdownload.download.db;

public record ArtworkRecord(
        long artworkId,
        String title,
        String folder,
        int count,
        String extensions,
        long time,
        boolean moved,
        String moveFolder,
        Long moveTime,
        Boolean isR18,
        Boolean isAi,
        Long authorId,
        String description
) {}
