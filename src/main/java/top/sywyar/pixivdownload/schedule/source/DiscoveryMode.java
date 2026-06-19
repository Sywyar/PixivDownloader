package top.sywyar.pixivdownload.schedule.source;

/** 计划任务来源的发现模式：决定调度壳用哪种共享扫描驱动派发。 */
public enum DiscoveryMode {
    /** ID 水位线增量（最新在前、只追加、ID 单调）。 */
    WATERMARK,
    /** 「翻页到底、命中第一个已下载即停」的边界扫描（排序非 ID 单调，不用水位线）。 */
    DOWNLOADED_BOUNDARY,
    /** 全量发现 + 跳过已下载（可选每轮上限）。 */
    FULL,
    /** 珍藏集混合来源（插画 + 小说两遍），由调度壳独立路径处理、不经共享扫描驱动。 */
    COLLECTION
}
