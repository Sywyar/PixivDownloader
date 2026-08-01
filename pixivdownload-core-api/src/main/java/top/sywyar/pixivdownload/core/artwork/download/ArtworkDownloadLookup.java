package top.sywyar.pixivdownload.core.artwork.download;

/**
 * 插画下载判重的核心查询端口。
 */
public interface ArtworkDownloadLookup {

    /**
     * 判断插画是否已经下载。
     *
     * @param artworkId   插画 id
     * @param verifyFiles 是否校验磁盘文件并修复陈旧历史
     */
    boolean isDownloaded(long artworkId, boolean verifyFiles);
}
