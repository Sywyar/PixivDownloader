package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 下载类型的页面能力。稳定的第三方扩展路径是插件自有独立页；
 * {@code unifiedGallery} 仅为旧契约兼容字段，不属于新的第三方 SDK 能力。
 * {@code reasonNamespace/reasonI18nKey} 可用于前端或管理页解释独立页边界。
 */
public record DownloadGalleryCapabilities(
        boolean unifiedGallery,
        boolean independentPage,
        String reasonNamespace,
        String reasonI18nKey
) {

    public DownloadGalleryCapabilities {
        reasonNamespace = blankToNull(reasonNamespace);
        reasonI18nKey = blankToNull(reasonI18nKey);
    }

    /**
     * 旧统一画廊产品路线的兼容声明；宿主不再把该值投影为可用能力。
     *
     * @deprecated 新插件请声明 {@link #independentPageOnly()}，不要依赖统一画廊路线。
    */
    @Deprecated(since = "1.0.0", forRemoval = false)
    public boolean unifiedGallery() {
        return unifiedGallery;
    }

    /** 默认：不声明页面能力。 */
    public static DownloadGalleryCapabilities none() {
        return new DownloadGalleryCapabilities(false, false, null, null);
    }

    /** 声明插件自有独立页，且不携带边界说明。 */
    public static DownloadGalleryCapabilities independentPageOnly() {
        return independentPageOnly(null, null);
    }

    /** 声明插件自有独立页，并提供可本地化的边界说明。 */
    public static DownloadGalleryCapabilities independentPageOnly(
            String reasonNamespace, String reasonI18nKey) {
        return new DownloadGalleryCapabilities(false, true, reasonNamespace, reasonI18nKey);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
