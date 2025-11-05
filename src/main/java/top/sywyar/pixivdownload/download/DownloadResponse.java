package top.sywyar.pixivdownload.download;

public class DownloadResponse {
    private boolean success;
    private String message;
    private String downloadPath;
    private int downloadedCount;

    // constructors
    public DownloadResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public DownloadResponse(boolean success, String message, String downloadPath, int downloadedCount) {
        this.success = success;
        this.message = message;
        this.downloadPath = downloadPath;
        this.downloadedCount = downloadedCount;
    }

    // getters and setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getDownloadPath() { return downloadPath; }
    public void setDownloadPath(String downloadPath) { this.downloadPath = downloadPath; }

    public int getDownloadedCount() { return downloadedCount; }
    public void setDownloadedCount(int downloadedCount) { this.downloadedCount = downloadedCount; }
}