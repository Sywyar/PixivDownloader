package top.sywyar.pixivdownload.core.hash;

import java.util.List;

/**
 * 核心图片哈希索引的只读语义端口。
 */
public interface ArtworkHashIndexQuery {

    List<ArtworkHashEntry> findAllEntries();

    ArtworkHashFingerprint fingerprint();
}
