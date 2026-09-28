package top.sywyar.pixivdownload.core.asset;

import java.io.IOException;
import java.util.Optional;

/** 按作品和页号保存媒体事实；不暴露数据库连接、表名或文件布局。 */
public interface ArtworkMediaStore {
    Optional<ArtworkMediaManifest> find(long artworkId, int page) throws IOException;

    /** 同步持久化单页，失败时调用方必须保留源文件；并发更新其它页不丢失。 */
    void save(long artworkId, int page, ArtworkMediaManifest media) throws IOException;
}
