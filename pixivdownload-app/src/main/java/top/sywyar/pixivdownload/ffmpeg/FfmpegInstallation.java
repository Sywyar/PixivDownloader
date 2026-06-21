package top.sywyar.pixivdownload.ffmpeg;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 已检测到的 FFmpeg 安装信息。
 */
public record FfmpegInstallation(Path ffmpegPath, Path ffprobePath, Path homeDir, Source source) {

    public boolean hasFfmpeg() {
        return ffmpegPath != null && Files.isRegularFile(ffmpegPath);
    }

    public boolean hasFfprobe() {
        return ffprobePath != null && Files.isRegularFile(ffprobePath);
    }

    public String sourceMessageCode() {
        return source.messageCode();
    }

    public enum Source {
        MANAGED("ffmpeg.source.managed"),
        BUNDLED("ffmpeg.source.bundled"),
        SYSTEM("ffmpeg.source.system");

        private final String messageCode;

        Source(String messageCode) {
            this.messageCode = messageCode;
        }

        public String messageCode() {
            return messageCode;
        }
    }
}
