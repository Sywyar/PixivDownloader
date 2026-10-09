package top.sywyar.pixivdownload.core.hash;

/**
 * 供相似图片查询消费的核心哈希索引投影。
 *
 * @param artworkId 插画作品标识
 * @param page 作品页码
 * @param dHash 用于相似度查询的差值哈希
 * @param aHash 可用时的均值哈希
 * @param title 作品标题
 * @param authorId 作者标识
 * @param authorName 作者显示名称
 * @param xRestrict 作品年龄分级
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
