package top.sywyar.pixivdownload.ffmpeg;

import java.nio.file.Files;
import java.nio.file.Path;

public record FfmpegInstallation(Path ffmpegPath, Path ffprobePath, Path homeDir, String sourceMessageCode) {
    public boolean hasFfmpeg() { return ffmpegPath != null && Files.isRegularFile(ffmpegPath); }
    public boolean hasFfprobe() { return ffprobePath != null && Files.isRegularFile(ffprobePath); }
    public Source source() {
        if ("ffmpeg.source.managed".equals(sourceMessageCode)) return Source.MANAGED;
        if ("ffmpeg.source.bundled".equals(sourceMessageCode)) return Source.BUNDLED;
        return Source.SYSTEM;
    }
    public enum Source { MANAGED, BUNDLED, SYSTEM }
}
