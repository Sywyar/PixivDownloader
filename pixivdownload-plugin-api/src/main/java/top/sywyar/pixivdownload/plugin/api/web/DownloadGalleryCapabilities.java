package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 下载类型对统一画廊的声明式能力。{@code unifiedGallery=false} 表示该类型不得被宣传为已接入统一画廊；
 * {@code reasonNamespace/reasonI18nKey} 可用于前端或管理页展示不可用原因。
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

    /** 默认：不声明统一画廊，也不声明独立页。 */
    public static DownloadGalleryCapabilities none() {
        return new DownloadGalleryCapabilities(false, false, null, null);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
