package top.sywyar.pixivdownload.download;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@AllArgsConstructor
public class DownloadResponse {
    // getters and setters
    private boolean success;
    private String message;
    private String downloadPath;
    private int downloadedCount;

    // constructors
    public DownloadResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }
}