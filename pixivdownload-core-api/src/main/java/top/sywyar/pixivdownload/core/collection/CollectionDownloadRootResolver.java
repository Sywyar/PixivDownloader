package top.sywyar.pixivdownload.core.collection;

import java.nio.file.Path;

/**
 * 按收藏夹配置解析作品下载根目录的核心语义端口。
 */
public interface CollectionDownloadRootResolver {

    /**
     * 同次读取下载目录与收藏夹名称；收藏夹不存在或未配置独立目录时使用 {@code defaultRoot}。
     *
     * @param collectionId 合集标识
     * @param defaultRoot 默认值根目录
     * @return 本次下载使用的目录与命名快照
     */
    Context resolve(long collectionId, Path defaultRoot);

    /**
     * 收藏夹目录与名称的同次读取结果。
     * @param root 有效下载根目录
     * @param name 当前名称；收藏夹不存在时为 null
     */
    record Context(Path root, String name) {}
}
