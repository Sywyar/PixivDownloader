package top.sywyar.pixivdownload.core.artwork.download;

/**
 * 已完成插画下载的核心累计统计端口。
 */
public interface ArtworkDownloadStatistics {

    /**
     * 累计一个完整下载的插画及其图片数。
     *
     * @param imageCount 图片数量
     */
    void recordCompleted(int imageCount);
}
