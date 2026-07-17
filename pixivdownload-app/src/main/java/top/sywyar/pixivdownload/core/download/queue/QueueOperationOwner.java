package top.sywyar.pixivdownload.core.download.queue;

/** 精确标识一批外置队列操作的 capability publication；与下载 descriptor publication 的计数域无关。 */
public record QueueOperationOwner(
        String pluginId,
        String packageId,
        long pluginGeneration,
        long capabilityPublicationId
) {

    public QueueOperationOwner {
        pluginId = requireText(pluginId, "plugin id");
        packageId = requireText(packageId, "package id");
        if (pluginGeneration < 0L) {
            throw new IllegalArgumentException("queue operation plugin generation must not be negative");
        }
        if (capabilityPublicationId <= 0L) {
            throw new IllegalArgumentException("queue operation capability publication id must be positive");
        }
    }

    public boolean matches(String expectedPluginId, String expectedPackageId, long expectedGeneration) {
        return pluginId.equals(expectedPluginId)
                && packageId.equals(expectedPackageId)
                && pluginGeneration == expectedGeneration;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("queue operation " + label + " must not be blank");
        }
        return value.trim();
    }
}
