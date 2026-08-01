package top.sywyar.pixivdownload.plugin.api.download.control;

/**
 * 宿主盖章的下载扩展 publication 身份。
 *
 * <p>{@code publicationId} 只属于下载 descriptor / UI 槽位 publication 计数域，不得与队列 capability
 * publication id 比较或互换。
 */
public record DownloadExtensionIdentity(
        String pluginId,
        String packageId,
        long generation,
        long publicationId
) {

    public DownloadExtensionIdentity {
        requireText(pluginId, "pluginId");
        requireText(packageId, "packageId");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        if (publicationId <= 0L) {
            throw new IllegalArgumentException("publicationId must be positive");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
