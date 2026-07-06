package top.sywyar.pixivdownload.douyin.db.history;

public record DouyinWorkRecord(
        String workId,
        String title,
        String folder,
        int count,
        String extensions,
        long time,
        boolean deleted,
        String kind,
        String sourceUrl,
        String canonicalUrl,
        String thumbnailUrl,
        String authorId,
        String authorName,
        String description,
        String itemTitle,
        String caption,
        Long publishTime,
        String collectionId,
        String collectionTitle,
        Integer collectionOrder
) {

    public DouyinWorkRecord withFolder(String folder) {
        return new DouyinWorkRecord(workId, title, folder, count, extensions, time, deleted, kind,
                sourceUrl, canonicalUrl, thumbnailUrl, authorId, authorName, description,
                itemTitle, caption, publishTime, collectionId, collectionTitle, collectionOrder);
    }

    public DouyinWorkRecord withDeleted(boolean deleted) {
        return new DouyinWorkRecord(workId, title, folder, count, extensions, time, deleted, kind,
                sourceUrl, canonicalUrl, thumbnailUrl, authorId, authorName, description,
                itemTitle, caption, publishTime, collectionId, collectionTitle, collectionOrder);
    }
}
