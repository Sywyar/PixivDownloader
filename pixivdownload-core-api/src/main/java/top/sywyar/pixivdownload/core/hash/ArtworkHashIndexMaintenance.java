package top.sywyar.pixivdownload.core.hash;

import java.util.List;
import java.util.OptionalInt;

/**
 * 核心图片哈希索引的重建语义端口。
 */
public interface ArtworkHashIndexMaintenance {

    /**
     * 返回插画作品数量。
     *
     * @return 方法返回的数值
     */
    long artworkCount();

    /**
     * 返回对应值。
     *
     * @return 方法返回的数值
     */
    int missingArtworkCount();

    /**
     * 返回对应值。
     *
     * @return 方法返回的列表
     */
    List<Long> artworkIdsNewestFirst();

    /**
     * 执行对应操作并返回结果。
     *
     * @param limit 限制值
     * @return 方法返回的列表
     */
    List<Long> artworkIdsMissingHashes(int limit);

    /**
     * 清除对应数据。
     */
    void clearAllHashes();

    /**
     * @return 作品存在时为写入的哈希页数；作品不存在时为空
     * @param artworkId 插画作品标识
     */
    OptionalInt rebuildArtwork(long artworkId);
}
