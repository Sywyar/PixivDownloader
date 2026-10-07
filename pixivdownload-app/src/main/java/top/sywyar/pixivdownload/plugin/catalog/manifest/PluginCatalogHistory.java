package top.sywyar.pixivdownload.plugin.catalog.manifest;

/** 主清单签名覆盖的历史文件引用；相对路径只允许同源 history 目录。 */
public record PluginCatalogHistory(String path, String sha256, long sizeBytes, int versions) {
    public static final int MAX_VERSIONS = 900;
    public static final long MAX_BYTES = 1024 * 1024;

    public PluginCatalogHistory {
        if (path == null || !path.matches("history/[a-z0-9][a-z0-9._-]{0,127}-[a-f0-9]{64}\\.json")
                || sha256 == null || !sha256.matches("[a-f0-9]{64}")
                || sizeBytes <= 0 || sizeBytes > MAX_BYTES || versions <= 0 || versions > MAX_VERSIONS) {
            throw new IllegalArgumentException("invalid plugin history reference");
        }
    }
}
