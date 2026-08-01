package top.sywyar.pixivdownload.core.ffmpeg;

import java.util.Objects;

/**
 * 宿主解析出的 FFmpeg 命令及其来源。
 */
public record ResolvedFfmpegCommand(String command, Source source) {

    public ResolvedFfmpegCommand {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(source, "source");
        if (command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
    }

    public enum Source {
        MANAGED,
        BUNDLED,
        SYSTEM,
        FALLBACK
    }
}
