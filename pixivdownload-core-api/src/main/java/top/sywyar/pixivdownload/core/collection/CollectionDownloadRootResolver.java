package top.sywyar.pixivdownload.core.collection;

import java.nio.file.Path;

/**
 * 按收藏夹配置解析作品下载根目录的核心语义端口。
 */
public interface CollectionDownloadRootResolver {

    /**
     * 收藏夹不存在或未配置独立目录时返回 {@code defaultRoot}。
     */
    Path resolveDownloadRoot(long collectionId, Path defaultRoot);
}
