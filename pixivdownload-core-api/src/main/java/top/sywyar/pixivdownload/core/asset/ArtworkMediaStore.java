package top.sywyar.pixivdownload.core.asset;

import java.io.IOException;
import java.util.Optional;

/** 按作品和页号保存媒体事实；不暴露数据库连接、表名或文件布局。 */
public interface ArtworkMediaStore {
    /**
     * @param artworkId 作品 ID
     * @param page 页码
     * @return 已保存的逐页媒体事实；未记录时为空
     * @throws IOException 持久化存储读取失败
     */
    Optional<ArtworkMediaManifest> find(long artworkId, int page) throws IOException;

    /**
     * 同步持久化单页，失败时调用方必须保留源文件；并发更新其它页不丢失。
     * @param artworkId 作品 ID
     * @param page 页码
     * @param media 待保存的逐页媒体事实
     * @throws IOException 持久化存储写入失败
     */
    void save(long artworkId, int page, ArtworkMediaManifest media) throws IOException;
}
