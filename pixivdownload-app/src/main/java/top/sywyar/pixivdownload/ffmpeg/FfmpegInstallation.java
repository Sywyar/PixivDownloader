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

    public enum Source {
        MANAGED,
        BUNDLED,
        SYSTEM
    }
}
