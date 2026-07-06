package top.sywyar.pixivdownload.douyin.db.history;

public record DouyinWorkFileRecord(
        String workId,
        int fileIndex,
        String mediaId,
        String mediaType,
        String fileName,
        String extension,
        Long bytes,
        String contentType,
        long createdTime
) {
}
