package top.sywyar.pixivdownload.download.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DownloadResponse {
    private boolean success;
    private String message;
    private String downloadPath;
    private int downloadedCount;
}
