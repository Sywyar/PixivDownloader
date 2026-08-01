package top.sywyar.pixivdownload.plugin.api.download.control;

/** 下载队列单项取消的稳定宿主结果；HTTP 状态与本地化响应由调用插件投影。 */
public enum DownloadQueueCancelResult {
    CANCELLED,
    DESCRIPTOR_NOT_FOUND,
    DESCRIPTOR_STALE,
    UNSUPPORTED,
    OPERATION_UNAVAILABLE
}
