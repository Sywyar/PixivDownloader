package top.sywyar.pixivdownload.core.artwork.download;

/**
 * 插画下载历史的核心写入端口。
 */
public interface ArtworkDownloadHistory {

    /**
     * 分配不与现有下载记录冲突的时间戳。
     *
     * @param preferredTime 首选 epoch 毫秒；非正数表示使用当前时间
     * @return 方法返回的数值
     */
    long allocateRecordTime(long preferredTime);

    /**
     * 记录一次媒体已经完整写入的插画下载。
     *
     * @param completion 完成结果
     */
    void record(ArtworkDownloadCompletion completion);

    /**
     * 返回尚未完成的选页布局，没有待完成选页时返回 null。
     * @param artworkId 作品 ID
     * @return 已登记的选页布局，缺失时为 null
     */
    PartialArtwork partial(long artworkId);

    /**
     * 在作品文件锁内开启选页。作品库已有完整作品时返回 false。
     * @param layout 本次选页的来源及输出布局
     * @return 能否开启选页
     */
    boolean startPartial(PartialArtwork layout);

    /**
     * 在同一事务中写入已保存各页的信息和完整作品记录。
     * @param completion 整部作品的完成结果
     */
    void completePartial(ArtworkDownloadCompletion completion);
}
