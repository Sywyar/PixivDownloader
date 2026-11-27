package top.sywyar.pixivdownload.core.db;

import top.sywyar.pixivdownload.core.work.model.FileManagement;

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
        Boolean isOriginal,
        Long createTime,
        Long updateTime,
        Long reuploadTime,
        FileManagement fileManagement
) {
    /** {@return whether the effective artwork directory is stored in move_folder} */
    public static boolean usesMoveFolder(String moveFolder) {
        return moveFolder != null && !moveFolder.isBlank();
    }

    /** {@return the directory selected by the artwork's stored path references} */
    public String effectiveFolder() {
        return usesMoveFolder(moveFolder) ? moveFolder : folder;
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
        Long seriesOrder,
        boolean deleted,
        Long uploadTime,
        Boolean isOriginal) {
        this(artworkId, title, folder, count, extensions, time, moved, moveFolder, moveTime, xRestrict, isAi, authorId, description, fileName, fileAuthorNameId, seriesId, seriesOrder, deleted, uploadTime, isOriginal, null, null, null, FileManagement.MANAGED);
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
