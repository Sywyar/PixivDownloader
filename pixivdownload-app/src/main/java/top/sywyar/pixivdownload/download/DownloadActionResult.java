package top.sywyar.pixivdownload.download;

import lombok.Getter;

@Getter
public class DownloadActionResult {
    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";
    public static final String EXISTS = "exists";

    private final String status;
    private final String message;

    private DownloadActionResult(String status, String message) {
        this.status = status;
        this.message = message;
    }

    public static DownloadActionResult success(String message) {
        return new DownloadActionResult(SUCCESS, message);
    }

    public static DownloadActionResult failed(String message) {
        return new DownloadActionResult(FAILED, message);
    }

    public static DownloadActionResult skipped(String message) {
        return new DownloadActionResult(SKIPPED, message);
    }

    public static DownloadActionResult exists(String message) {
        return new DownloadActionResult(EXISTS, message);
    }
}
