package top.sywyar.pixivdownload.download.response.status;

import lombok.Builder;
import lombok.Getter;

/** 下载控制面的通用响应，保持既有 HTTP 字段。 */
@Getter
@Builder
public class DownloadResponse {
    private java.util.UUID taskId;
    private boolean success;
    private String message;
    private String downloadPath;
    private int downloadedCount;
}
