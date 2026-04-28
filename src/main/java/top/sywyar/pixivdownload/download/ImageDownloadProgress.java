package top.sywyar.pixivdownload.download;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImageDownloadProgress {
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    private final String status;
    private final Integer imageNumber;
    private final Integer totalImages;
    private final Long downloadedBytes;
    private final Long totalBytes;
    private final Integer progress;
}
