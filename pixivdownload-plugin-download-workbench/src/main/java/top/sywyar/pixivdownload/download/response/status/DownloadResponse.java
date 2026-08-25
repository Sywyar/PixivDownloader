package top.sywyar.pixivdownload.download.response.status;

import lombok.Builder;
import lombok.Getter;

/** 下载工作台控制面响应；由插件自身拥有，避免复用宿主应用 DTO。 */
@Getter
@Builder
public class DownloadResponse {
    private boolean success;
    private String message;
    private String downloadPath;
    private int downloadedCount;
}
