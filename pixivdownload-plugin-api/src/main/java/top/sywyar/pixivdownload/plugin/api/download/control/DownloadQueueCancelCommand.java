package top.sywyar.pixivdownload.plugin.api.download.control;

import java.util.Objects;

/**
 * 按精确 descriptor publication 取消不透明队列作品键的命令。
 *
 * @param queueType 接收取消命令的队列类型
 * @param workKey 队列拥有的不透明作品键
 * @param expectedPublication 发现时绑定的下载扩展发布身份
 */
public record DownloadQueueCancelCommand(
        String queueType,
        String workKey,
        DownloadExtensionIdentity expectedPublication
) {

    /**
     * 创建 {@code DownloadQueueCancelCommand} 实例。
     *
     * @param queueType 队列类型
     * @param workKey 作品键
     * @param expectedPublication 期望值发布项
     */
    public DownloadQueueCancelCommand {
        if (queueType == null || queueType.isBlank()) {
            throw new IllegalArgumentException("queueType must not be blank");
        }
        if (workKey == null || workKey.isBlank()) {
            throw new IllegalArgumentException("workKey must not be blank");
        }
        Objects.requireNonNull(expectedPublication, "expected download extension publication");
    }
}
