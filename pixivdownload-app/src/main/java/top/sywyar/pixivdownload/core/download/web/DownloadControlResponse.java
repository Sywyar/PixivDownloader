package top.sywyar.pixivdownload.core.download.web;

import java.util.UUID;

/** 通用控制面的既有 HTTP 字段；不保存具体作品实现。 */
public record DownloadControlResponse(UUID taskId, boolean success, String message,
                                      String downloadPath, int downloadedCount) {
    public static DownloadControlResponse success(String message) {
        return new DownloadControlResponse(null, true, message, null, 0);
    }
}
