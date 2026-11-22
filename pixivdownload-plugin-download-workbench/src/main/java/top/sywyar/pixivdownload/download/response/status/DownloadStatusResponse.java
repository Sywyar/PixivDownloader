package top.sywyar.pixivdownload.download.response.status;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

import lombok.Builder;
import lombok.Getter;
import top.sywyar.pixivdownload.core.work.WorkActionResult;
import top.sywyar.pixivdownload.download.ImageDownloadProgress;
import top.sywyar.pixivdownload.download.UgoiraProgress;

@Getter
@Builder
public class DownloadStatusResponse {
    private boolean success;
    private String message;
    private Long artworkId;
    @HttpApiNullable private String title;
    private int totalImages;
    private int downloadedCount;
    private int currentImageIndex;
    private boolean completed;
    private boolean failed;
    @HttpApiNullable private String errorCode;
    private boolean cancelled;
    private double progressPercentage;
    @HttpApiNullable private String downloadPath;
    @HttpApiNullable private WorkActionResult bookmarkResult;
    @HttpApiNullable private WorkActionResult collectionResult;
    @HttpApiNullable private UgoiraProgress ugoiraProgress;
    @HttpApiNullable private ImageDownloadProgress imageProgress;
}
