package top.sywyar.pixivdownload.download.response.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import lombok.Builder;
import lombok.Getter;
import top.sywyar.pixivdownload.core.work.WorkActionResult;
import top.sywyar.pixivdownload.download.ImageDownloadProgress;
import top.sywyar.pixivdownload.download.UgoiraProgress;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SseStatusData {
    private final Long artworkId;
    private final String title;
    private final java.util.Map<String, String> presentationAttributes;
    private final String status;
    private final String message;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final boolean success;
    private final Integer currentImageIndex;
    private final Integer totalImages;
    private final Integer downloadedCount;
    private final Boolean completed;
    private final Boolean failed;
    private final String errorCode;
    private final Boolean cancelled;
    private final String folderName;
    @Schema(minimum = "0", maximum = "100")
    private final Integer progress;
    private final WorkActionResult bookmarkResult;
    private final WorkActionResult collectionResult;
    @HttpApiNullable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private final UgoiraProgress ugoiraProgress;
    @HttpApiNullable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private final ImageDownloadProgress imageProgress;
}
