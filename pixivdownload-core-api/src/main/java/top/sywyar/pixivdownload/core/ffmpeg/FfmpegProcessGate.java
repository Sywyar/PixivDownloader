package top.sywyar.pixivdownload.core.ffmpeg;

import java.util.function.BooleanSupplier;

/**
 * 宿主拥有的 FFmpeg 进程并发预算，所有媒体任务共享；等待期间支持取消。
 */
@FunctionalInterface
public interface FfmpegProcessGate {
    Permit acquire(BooleanSupplier cancellationRequested);

    /** 进程及其子进程退出后关闭；重复关闭不重复释放预算。 */
    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
