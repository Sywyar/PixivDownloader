package top.sywyar.pixivdownload.core.artwork.download;

/**
 * 插画下载终态的核心统计端口。
 */
public interface ArtworkDownloadStatistics {

    /**
     * 累计一个完整下载的插画及其图片数。
     *
     * @param imageCount 图片数量
     */
    void recordCompleted(int imageCount);

    /**
     * 累计一个未完整成功且未取消的插画下载任务。
     */
    void recordFailed();

    /**
     * 读取宿主本地日期内已经结束的插画下载任务计数。
     *
     * @return 当日成功与失败计数
     */
    DailyOutcomes today();

    /**
     * 一个本地日期内的插画下载终态计数。
     *
     * @param completed 完整成功的任务数
     * @param failed 未完整成功且未取消的任务数
     */
    record DailyOutcomes(int completed, int failed) {
        public DailyOutcomes {
            if (completed < 0 || failed < 0) {
                throw new IllegalArgumentException("download outcome counts must not be negative");
            }
        }
    }
}
