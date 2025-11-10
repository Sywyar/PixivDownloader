package top.sywyar.pixivdownload.download.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@AllArgsConstructor
public class DownloadStatusResponse {
    // getters and setters
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

}