package top.sywyar.pixivdownload.download;

public class DownloadStatusResponse {
    private boolean success;
    private String message;
    private Long artworkId;
    private int totalImages;
    private int downloadedCount;
    private int currentImageIndex;
    private boolean completed;
    private boolean failed;
    private boolean cancelled;
    private double progressPercentage;
    private String downloadPath;

    public DownloadStatusResponse(boolean success, String message, Long artworkId) {
        this.success = success;
        this.message = message;
        this.artworkId = artworkId;
    }

    public DownloadStatusResponse(boolean success, String message, Long artworkId,
                                  int totalImages, int downloadedCount, int currentImageIndex,
                                  boolean completed, boolean failed, boolean cancelled,
                                  double progressPercentage, String downloadPath) {
        this.success = success;
        this.message = message;
        this.artworkId = artworkId;
        this.totalImages = totalImages;
        this.downloadedCount = downloadedCount;
        this.currentImageIndex = currentImageIndex;
        this.completed = completed;
        this.failed = failed;
        this.cancelled = cancelled;
        this.progressPercentage = progressPercentage;
        this.downloadPath = downloadPath;
    }

    // getters and setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Long getArtworkId() { return artworkId; }
    public void setArtworkId(Long artworkId) { this.artworkId = artworkId; }

    public int getTotalImages() { return totalImages; }
    public void setTotalImages(int totalImages) { this.totalImages = totalImages; }

    public int getDownloadedCount() { return downloadedCount; }
    public void setDownloadedCount(int downloadedCount) { this.downloadedCount = downloadedCount; }

    public int getCurrentImageIndex() { return currentImageIndex; }
    public void setCurrentImageIndex(int currentImageIndex) { this.currentImageIndex = currentImageIndex; }

    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }

    public boolean isFailed() { return failed; }
    public void setFailed(boolean failed) { this.failed = failed; }

    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    public double getProgressPercentage() { return progressPercentage; }
    public void setProgressPercentage(double progressPercentage) { this.progressPercentage = progressPercentage; }

    public String getDownloadPath() { return downloadPath; }
    public void setDownloadPath(String downloadPath) { this.downloadPath = downloadPath; }
}