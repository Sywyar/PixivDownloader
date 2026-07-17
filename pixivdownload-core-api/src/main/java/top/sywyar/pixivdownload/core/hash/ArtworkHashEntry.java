package top.sywyar.pixivdownload.core.hash;

/**
 * 供相似图片查询消费的核心哈希索引投影。
 */
public record ArtworkHashEntry(
        long artworkId,
        int page,
        long dHash,
        Long aHash,
        String title,
        Long authorId,
        String authorName,
        Integer xRestrict
) {
}
