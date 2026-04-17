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

    public String sourceLabel() {
        return source.displayName();
    }

    public enum Source {
        MANAGED("用户目录"),
        BUNDLED("安装包内置"),
        SYSTEM("系统 PATH");

        private final String displayName;

        Source(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }
}
