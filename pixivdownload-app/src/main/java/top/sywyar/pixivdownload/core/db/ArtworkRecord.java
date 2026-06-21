package top.sywyar.pixivdownload.core.db;

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
        Integer xRestrict,
        Boolean isAi,
        Long authorId,
        String description,
        Long fileName,
        Long fileAuthorNameId,
        Long seriesId,
        Long seriesOrder,
        boolean deleted,
        Long uploadTime,
        Boolean isOriginal
) {
    public ArtworkRecord(long artworkId,
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
                         boolean deleted) {
        this(artworkId, title, folder, count, extensions, time, moved, moveFolder, moveTime,
                xRestrict, isAi, authorId, description, fileName, fileAuthorNameId, seriesId, seriesOrder,
                deleted, null, null);
    }

    public ArtworkRecord(long artworkId,
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
                         Long seriesOrder) {
        this(artworkId, title, folder, count, extensions, time, moved, moveFolder, moveTime,
                xRestrict, isAi, authorId, description, fileName, fileAuthorNameId, seriesId, seriesOrder,
                false, null, null);
    }

    public ArtworkRecord(long artworkId,
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
                         Long fileAuthorNameId) {
        this(artworkId, title, folder, count, extensions, time, moved, moveFolder, moveTime,
                xRestrict, isAi, authorId, description, fileName, fileAuthorNameId, null, null, false, null, null);
    }

    public ArtworkRecord(long artworkId,
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
                         Long fileName) {
        this(artworkId, title, folder, count, extensions, time, moved, moveFolder, moveTime,
                xRestrict, isAi, authorId, description, fileName, null, null, null, false, null, null);
    }

    public ArtworkRecord(long artworkId,
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
                         String description) {
        this(artworkId, title, folder, count, extensions, time, moved, moveFolder, moveTime,
                xRestrict, isAi, authorId, description, null, null, null, null, false, null, null);
    }
}
