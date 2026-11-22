package top.sywyar.pixivdownload.download;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImageDownloadProgress {
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    @Schema(allowableValues = {STATUS_RUNNING, STATUS_COMPLETED, STATUS_FAILED})
    private final String status;
    @Schema(examples = {"processing", "verifying", "ffmpeg-waiting", "ffmpeg", "thumbnail", "finalizing"})
    private final String phase;
    private final String outputFormat;
    private final Integer outputIndex;
    private final Integer outputCount;
    private final Integer imageNumber;
    private final Integer totalImages;
    private final Long downloadedBytes;
    private final Long totalBytes;
    private final Integer progress;
    /** 同一作品仍在处理的图片；只包含一层快照，不重复携带传输字节。 */
    private final java.util.List<ImageDownloadProgress> processing;
}
