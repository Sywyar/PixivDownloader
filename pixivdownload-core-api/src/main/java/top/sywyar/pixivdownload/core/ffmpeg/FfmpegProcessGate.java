package top.sywyar.pixivdownload.core.ffmpeg;

import java.util.function.BooleanSupplier;

/**
 * 宿主拥有的 FFmpeg 进程并发预算，所有媒体任务共享；等待期间支持取消。
 */
@FunctionalInterface
public interface FfmpegProcessGate {
    /**
     * 等待并取得 FFmpeg 进程额度，等待期间响应取消请求。
     *
     * @param cancellationRequested 等待期间检查的取消信号
     * @return 当前任务取得的进程额度，进程退出后必须关闭
     */
    Permit acquire(BooleanSupplier cancellationRequested);

    /** 进程及其子进程退出后关闭；重复关闭不重复释放预算。 */
    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
