package top.sywyar.pixivdownload.core.hash;

import java.util.List;

/**
 * 核心图片哈希索引的只读语义端口。
 */
public interface ArtworkHashIndexQuery {

    /**
     * 返回全部条目列表。
     *
     * @return 方法返回的列表
     */
    List<ArtworkHashEntry> findAllEntries();

    /**
     * 返回对应值。
     *
     * @return 方法返回的 {@code ArtworkHashFingerprint} 实例
     */
    ArtworkHashFingerprint fingerprint();
}
