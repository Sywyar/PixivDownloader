package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 下载类型的页面能力。稳定的第三方扩展路径是插件自有独立页；
 * {@code reasonNamespace/reasonI18nKey} 可用于前端或管理页解释独立页边界。
 */
public record DownloadGalleryCapabilities(
        boolean independentPage,
        String reasonNamespace,
        String reasonI18nKey
) {

    public DownloadGalleryCapabilities {
        reasonNamespace = blankToNull(reasonNamespace);
        reasonI18nKey = blankToNull(reasonI18nKey);
    }

    /** 默认：不声明页面能力。 */
    public static DownloadGalleryCapabilities none() {
        return new DownloadGalleryCapabilities(false, null, null);
    }

    /** 声明插件自有独立页，且不携带边界说明。 */
    public static DownloadGalleryCapabilities independentPageOnly() {
        return independentPageOnly(null, null);
    }

    /** 声明插件自有独立页，并提供可本地化的边界说明。 */
    public static DownloadGalleryCapabilities independentPageOnly(
            String reasonNamespace, String reasonI18nKey) {
        return new DownloadGalleryCapabilities(true, reasonNamespace, reasonI18nKey);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
