package top.sywyar.pixivdownload.core.collection;

import java.nio.file.Path;

/**
 * 按收藏夹配置解析作品下载根目录的核心语义端口。
 */
public interface CollectionDownloadRootResolver {

    /**
     * 收藏夹不存在或未配置独立目录时返回 {@code defaultRoot}。
     *
     * @param collectionId 合集标识
     * @param defaultRoot 默认值根目录
     * @return 方法返回的 {@code Path} 实例
     */
    Path resolveDownloadRoot(long collectionId, Path defaultRoot);
}
