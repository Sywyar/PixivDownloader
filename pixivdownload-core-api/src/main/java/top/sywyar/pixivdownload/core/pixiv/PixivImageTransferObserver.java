package top.sywyar.pixivdownload.core.pixiv;

/**
 * Pixiv 图片流式传输观察器。
 */
public interface PixivImageTransferObserver {

    long MAX_IMAGE_BYTES = 100L * 1024L * 1024L;
    long MAX_TASK_BYTES = 1024L * 1024L * 1024L;

    /**
     * 本次传输允许写入的最大字节数；实现还必须受 {@link #MAX_IMAGE_BYTES} 硬上限约束。
     */
    default long maximumBytes() {
        return MAX_IMAGE_BYTES;
    }

    /**
     * 每个数据块写入前执行的取消检查；需要取消时可抛出运行期异常。
     */
    default void checkCancelled() {
    }

    /**
     * 收到响应后报告 Content-Type；缺失时报告 {@code null}。
     */
    default void onContentType(String contentType) {
    }

    /**
     * 收到响应后报告内容长度；未知或非正值统一报告为 {@code 0}。
     */
    default void onContentLength(long contentLength) {
    }

    /**
     * 报告已写入目标文件的累计字节数。
     */
    default void onBytesTransferred(long transferredBytes) {
    }
}
