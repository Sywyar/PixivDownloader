package top.sywyar.pixivdownload.core.ffmpeg;

import java.util.Objects;

/**
 * 宿主解析出的 FFmpeg 命令及其来源。
 */
public record ResolvedFfmpegCommand(String command, Source source) {

    /**
     * 创建 {@code ResolvedFfmpegCommand} 实例。
     *
     * @param command 命令
     * @param source 数据来源
     */
    public ResolvedFfmpegCommand {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(source, "source");
        if (command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
    }

    /**
     * 表示 {@code 该枚举值} 状态。
     */
    public enum Source {
        /**
         * 表示 {@code MANAGED} 状态。
         */
        MANAGED,
        /**
         * 表示 {@code BUNDLED} 状态。
         */
        BUNDLED,
        /**
         * 表示 {@code SYSTEM} 状态。
         */
        SYSTEM,
        /**
         * 表示 {@code FALLBACK}。
         */
        FALLBACK
    }
}
