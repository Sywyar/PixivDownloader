package top.sywyar.pixivdownload.download.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SseStatusData {
    private final Long artworkId;
    private final String status;
    private final String message;
    private final boolean success;
    private final Integer currentImageIndex;
    private final Integer totalImages;
    private final Integer downloadedCount;
    private final Boolean completed;
    private final Boolean failed;
    private final Boolean cancelled;
    private final String folderName;
    private final Integer progress;
}
