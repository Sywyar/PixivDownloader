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
        Integer xRestrict,
        Boolean isAi,
        Long authorId,
        String description,
        Long fileName,
        Long fileAuthorNameId,
        Long seriesId,
        Long seriesOrder,
        boolean deleted
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
                         Long seriesOrder) {
        this(artworkId, title, folder, count, extensions, time, moved, moveFolder, moveTime,
                xRestrict, isAi, authorId, description, fileName, fileAuthorNameId, seriesId, seriesOrder, false);
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
                xRestrict, isAi, authorId, description, fileName, fileAuthorNameId, null, null, false);
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
                xRestrict, isAi, authorId, description, fileName, null, null, null, false);
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
                xRestrict, isAi, authorId, description, null, null, null, null, false);
    }
}
