package top.sywyar.pixivdownload.duplicate;

public record ImageHashRow(
        long artworkId,
        int page,
        String ext,
        long dHash,
        Long aHash,
        long createdTime,
        String title,
        Long authorId,
        String authorName,
        Integer xRestrict
) {
}
