package top.sywyar.pixivdownload.download;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UgoiraProgress {
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    public static final String PHASE_ZIP = "zip";
    public static final String PHASE_EXTRACT = "extract";
    public static final String PHASE_FFMPEG = "ffmpeg";

    private final String phase;
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
}
