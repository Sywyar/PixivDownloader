package top.sywyar.pixivdownload.plugin.catalog.page;

import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogEntry;

/** 单个插件的展示信息与一页版本摘要。 */
public record PluginCatalogDetailPage(PluginCatalogEntry item, String generation, String nextCursor,
                                      Long totalApproximate, boolean stale) {
    public PluginCatalogDetailPage staleCopy() {
        return new PluginCatalogDetailPage(item, generation, nextCursor, totalApproximate, true);
    }
}
