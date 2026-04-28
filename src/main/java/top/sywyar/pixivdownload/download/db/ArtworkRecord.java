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
        Long fileAuthorNameId
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
                         Long fileName) {
        this(artworkId, title, folder, count, extensions, time, moved, moveFolder, moveTime,
                xRestrict, isAi, authorId, description, fileName, null);
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
                xRestrict, isAi, authorId, description, null, null);
    }
}
