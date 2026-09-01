package top.sywyar.pixivdownload.plugin.catalog.page;

import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogEntry;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogPackage;

/** paged-v2 详情/版本响应；对应端点只填其一。 */
public record PagedCatalogItemDocument(String generation, PluginCatalogEntry item,
                                       PluginCatalogPackage version, String nextCursor,
                                       Long totalApproximate) { }
