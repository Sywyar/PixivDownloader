package top.sywyar.pixivdownload.core.ffmpeg;

/**
 * 解析宿主运行环境可执行的 FFmpeg 命令。
 */
public interface FfmpegCommandResolver {

    /**
     * 返回对应值。
     *
     * @return 方法返回的 {@code ResolvedFfmpegCommand} 实例
     */
    ResolvedFfmpegCommand resolve();

    /**
     * 解析宿主提供的媒体工具；默认保留同目录工具的既有规则。
     * @param tool 要执行的工具
     * @return 工具命令与来源；实际安装探测由宿主实现负责
     */
    default ResolvedFfmpegCommand resolve(FfmpegRunner.Tool tool) {
        java.util.Objects.requireNonNull(tool, "tool");
        ResolvedFfmpegCommand resolved = resolve();
        if (tool == FfmpegRunner.Tool.FFMPEG) return resolved;
        java.nio.file.Path executable = java.nio.file.Path.of(resolved.command());
        String name = resolved.command().toLowerCase(java.util.Locale.ROOT).endsWith(".exe") ? "ffprobe.exe" : "ffprobe";
        return new ResolvedFfmpegCommand(executable.getParent() == null ? name
                : executable.resolveSibling(name).toString(), resolved.source());
    }
}
