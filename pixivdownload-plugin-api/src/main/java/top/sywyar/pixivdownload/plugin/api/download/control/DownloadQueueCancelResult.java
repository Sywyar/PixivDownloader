package top.sywyar.pixivdownload.plugin.api.download.control;

/** 下载队列单项取消的稳定宿主结果；HTTP 状态与本地化响应由调用插件投影。 */
public enum DownloadQueueCancelResult {
    /**
     * 表示 {@code CANCELLED} 状态。
     */
    CANCELLED,
    /**
     * 表示 {@code DESCRIPTOR_NOT_FOUND} 状态。
     */
    DESCRIPTOR_NOT_FOUND,
    /**
     * 表示 {@code DESCRIPTOR_STALE} 状态。
     */
    DESCRIPTOR_STALE,
    /**
     * 表示 {@code UNSUPPORTED} 状态。
     */
    UNSUPPORTED,
    /**
     * 表示 {@code OPERATION_UNAVAILABLE}。
     */
    OPERATION_UNAVAILABLE
}
