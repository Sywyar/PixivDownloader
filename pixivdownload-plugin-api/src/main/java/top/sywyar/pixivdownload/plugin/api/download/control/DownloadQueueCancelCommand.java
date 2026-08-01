package top.sywyar.pixivdownload.plugin.api.download.control;

import java.util.Objects;

/** 按精确 descriptor publication 取消不透明队列作品键的命令。 */
public record DownloadQueueCancelCommand(
        String queueType,
        String workKey,
        DownloadExtensionIdentity expectedPublication
) {

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
