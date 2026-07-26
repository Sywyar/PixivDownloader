package top.sywyar.pixivdownload.core.ffmpeg;

/**
 * 解析宿主运行环境可执行的 FFmpeg 命令。
 */
public interface FfmpegCommandResolver {

    ResolvedFfmpegCommand resolve();
}
