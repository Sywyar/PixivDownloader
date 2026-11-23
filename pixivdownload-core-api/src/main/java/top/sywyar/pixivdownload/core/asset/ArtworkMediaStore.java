package top.sywyar.pixivdownload.core.asset;

import java.io.IOException;
import java.util.Optional;

/** 按作品和页号保存媒体事实；不暴露数据库连接、表名或文件布局。 */
public interface ArtworkMediaStore {
    /**
     * 读取指定作品页已经登记的媒体产物事实。
     *
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

    /**
     * 保存未完成的选页，保留作品库已有的媒体记录。
     * @param artworkId 作品 ID
     * @param page 原始页索引
     * @param media 该页的媒体清单
     * @param fingerprints 该页产物的文件指纹
     * @throws IOException 存储写入失败
     */
    void savePartial(long artworkId, int page, ArtworkMediaManifest media, String fingerprints) throws IOException;
}
