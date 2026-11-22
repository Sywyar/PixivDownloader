package top.sywyar.pixivdownload.download;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UgoiraProgress {
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    public static final String PHASE_ZIP = "zip";
    public static final String PHASE_EXTRACT = "extract";
    public static final String PHASE_FFMPEG = "ffmpeg";
    public static final String PHASE_WAITING_FFMPEG = "ffmpeg-waiting";
    public static final String PHASE_FINALIZING = "finalizing";

    @Schema(allowableValues = {PHASE_ZIP, PHASE_EXTRACT, PHASE_WAITING_FFMPEG, PHASE_FFMPEG, PHASE_FINALIZING})
    private final String phase;
    @Schema(allowableValues = {STATUS_RUNNING, STATUS_COMPLETED, STATUS_FAILED})
    private final String status;
    private final String message;
    private final Integer attempt;
    private final Integer maxAttempts;

    private final Long zipDownloadedBytes;
    private final Long zipTotalBytes;
    private final Integer zipProgress;

    private final Integer extractedFrames;
    private final Integer totalFrames;

    private final Long ffmpegOutTimeMs;
    private final Long ffmpegDurationMs;
    private final Integer ffmpegProgress;
    private final String outputFormat;
    private final Integer outputIndex;
    private final Integer outputCount;
}
