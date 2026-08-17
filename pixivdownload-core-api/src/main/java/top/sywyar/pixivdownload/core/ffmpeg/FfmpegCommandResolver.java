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
}
