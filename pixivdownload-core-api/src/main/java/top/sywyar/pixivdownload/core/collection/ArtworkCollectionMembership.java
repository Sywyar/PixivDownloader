package top.sywyar.pixivdownload.core.collection;

/**
 * 把插画作品加入收藏夹的核心语义端口。
 */
public interface ArtworkCollectionMembership {

    /**
     * @return 新增了作品与收藏夹关系时为 {@code true}；关系已存在时为 {@code false}
     */
    boolean addArtwork(long collectionId, long artworkId);
}
