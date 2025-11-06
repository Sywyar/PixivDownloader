package top.sywyar.pixivdownload.download;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter
@Getter
public class DownloadStatus {
    // getters and setters
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