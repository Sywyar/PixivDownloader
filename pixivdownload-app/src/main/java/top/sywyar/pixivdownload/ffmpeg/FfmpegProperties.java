package top.sywyar.pixivdownload.ffmpeg;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ffmpeg")
public class FfmpegProperties {
    public static final int DEFAULT_MAX_CONCURRENT = 2;
    public static final int MAX_CONCURRENT_LIMIT = 8;

    private int maxConcurrent = DEFAULT_MAX_CONCURRENT;

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        if (maxConcurrent < 1 || maxConcurrent > MAX_CONCURRENT_LIMIT) {
            throw new IllegalArgumentException("ffmpeg.max-concurrent must be between 1 and " + MAX_CONCURRENT_LIMIT);
        }
        this.maxConcurrent = maxConcurrent;
    }
}
