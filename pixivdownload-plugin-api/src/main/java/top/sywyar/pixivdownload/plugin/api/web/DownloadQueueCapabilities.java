package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 某下载类型在跨类型队列宿主中的操作能力。清空全部 / 按 owner 清空是下载类型的基础队列能力；
 * 单项取消按类型自愿提供。
 */
public record DownloadQueueCapabilities(
        boolean clearAll,
        boolean clearForOwner,
        boolean cancel
) {

    /** 常见能力：支持两类清空，不支持单项取消。 */
    public static DownloadQueueCapabilities clearOnly() {
        return new DownloadQueueCapabilities(true, true, false);
    }

    /** 完整队列能力：两类清空 + 单项取消。 */
    public static DownloadQueueCapabilities full() {
        return new DownloadQueueCapabilities(true, true, true);
    }
}
