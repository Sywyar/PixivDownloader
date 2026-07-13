package top.sywyar.pixivdownload.plugin.registry;

/** 下载工作台扩展贡献的权威 owner 身份。 */
public record DownloadExtensionOwner(
        String featurePluginId,
        String packageId,
        long generation
) {

    public DownloadExtensionOwner {
        featurePluginId = requireText(featurePluginId, "feature plugin id");
        packageId = requireText(packageId, "plugin package id");
        if (generation < 0L) {
            throw new IllegalArgumentException("plugin generation must not be negative");
        }
    }

    static DownloadExtensionOwner from(PluginRegistry.RegisteredPlugin registered) {
        return new DownloadExtensionOwner(registered.id(), registered.packageId(), registered.generation());
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }
}
