package top.sywyar.pixivdownload.core.hash;

import java.util.List;
import java.util.OptionalInt;

/**
 * 核心图片哈希索引的重建语义端口。
 */
public interface ArtworkHashIndexMaintenance {

    long artworkCount();

    int missingArtworkCount();

    List<Long> artworkIdsNewestFirst();

    List<Long> artworkIdsMissingHashes(int limit);

    void clearAllHashes();

    /**
     * @return 作品存在时为写入的哈希页数；作品不存在时为空
     */
    OptionalInt rebuildArtwork(long artworkId);
}
