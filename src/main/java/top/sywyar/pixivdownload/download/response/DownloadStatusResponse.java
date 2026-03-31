package top.sywyar.pixivdownload.download.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DownloadStatusResponse {
    private boolean success;
    private String message;
    private Long artworkId;
    private String title;
    private int totalImages;
    private int downloadedCount;
    private int currentImageIndex;
    private boolean completed;
    private boolean failed;
    private boolean cancelled;
    private double progressPercentage;
    private String downloadPath;
}
