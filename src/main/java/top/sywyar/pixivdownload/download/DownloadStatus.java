package top.sywyar.pixivdownload.download;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DownloadStatus {
    private Long artworkId;
    private String title;
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
    private DownloadActionResult bookmarkResult;
    private DownloadActionResult collectionResult;
    private UgoiraProgress ugoiraProgress;
    private ImageDownloadProgress imageProgress;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    public DownloadStatus(Long artworkId, String title, int totalImages) {
        this.artworkId = artworkId;
        this.title = title;
        this.totalImages = totalImages;
        this.startTime = LocalDateTime.now();
        this.downloadedCount = 0;
        this.currentImageIndex = -1;
        this.completed = false;
        this.failed = false;
        this.cancelled = false;
    }

    // 计算进度百分比
    public double getProgressPercentage() {
        if (totalImages == 0) return 0;
        return (double) downloadedCount / totalImages * 100;
    }

    public String getStatusMessageCode() {
        if (cancelled) return "download.status.cancelled";
        if (failed) return "download.status.failed";
        if (completed) return "download.status.completed";
        if (currentImageIndex >= 0) return "download.status.in-progress";
        return "download.status.pending";
    }

    public Object[] getStatusMessageArgs() {
        if (failed) {
            return new Object[]{errorMessage == null ? "" : errorMessage};
        }
        if (completed) {
            return new Object[]{String.valueOf(successCount), String.valueOf(totalImages)};
        }
        if (currentImageIndex >= 0) {
            return new Object[]{String.valueOf(currentImageIndex + 1), String.valueOf(totalImages)};
        }
        return new Object[0];
    }
}
