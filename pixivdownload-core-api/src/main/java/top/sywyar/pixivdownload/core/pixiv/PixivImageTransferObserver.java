package top.sywyar.pixivdownload.core.pixiv;

/**
 * Pixiv 图片流式传输观察器。
 */
public interface PixivImageTransferObserver {

    /**
     * 每个数据块写入前执行的取消检查；需要取消时可抛出运行期异常。
     */
    default void checkCancelled() {
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
