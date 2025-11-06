package top.sywyar.pixivdownload.download;

import java.time.LocalDateTime;

public class DownloadStatus {
    private Long artworkId;
    private int totalImages;
    private int downloadedCount;
    private int currentImageIndex;
    private boolean completed;
    private boolean failed;
    private boolean cancelled;
    private String errorMessage;
    private String folderName;
    private String downloadPath;
    private int successCount;
    private int failedCount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    public DownloadStatus(Long artworkId, int totalImages) {
        this.artworkId = artworkId;
        this.totalImages = totalImages;
        this.startTime = LocalDateTime.now();
        this.downloadedCount = 0;
        this.currentImageIndex = -1;
        this.completed = false;
        this.failed = false;
        this.cancelled = false;
    }

    // getters and setters
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

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getFolderName() { return folderName; }
    public void setFolderName(String folderName) { this.folderName = folderName; }

    public String getDownloadPath() { return downloadPath; }
    public void setDownloadPath(String downloadPath) { this.downloadPath = downloadPath; }

    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }

    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    // 计算进度百分比
    public double getProgressPercentage() {
        if (totalImages == 0) return 0;
        return (double) downloadedCount / totalImages * 100;
    }

    // 获取当前状态描述
    public String getStatusDescription() {
        if (cancelled) return "已取消";
        if (failed) return "失败: " + errorMessage;
        if (completed) return "已完成 (" + successCount + "/" + totalImages + ")";
        if (currentImageIndex >= 0) return "下载中 (" + (currentImageIndex + 1) + "/" + totalImages + ")";
        return "等待开始";
    }
}