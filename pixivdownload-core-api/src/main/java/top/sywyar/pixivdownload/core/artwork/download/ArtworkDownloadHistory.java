package top.sywyar.pixivdownload.core.artwork.download;

/**
 * 插画下载历史的核心写入端口。
 */
public interface ArtworkDownloadHistory {

    /**
     * 分配不与现有下载记录冲突的时间戳。
     *
     * @param preferredTime 首选 epoch 毫秒；非正数表示使用当前时间
     */
    long allocateRecordTime(long preferredTime);

    /**
     * 记录一次媒体已经完整写入的插画下载。
     */
    void record(ArtworkDownloadCompletion completion);
}
