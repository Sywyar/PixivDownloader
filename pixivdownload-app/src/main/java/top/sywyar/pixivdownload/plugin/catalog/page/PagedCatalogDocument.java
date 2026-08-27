package top.sywyar.pixivdownload.plugin.catalog.page;

import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogEntry;

import java.util.List;
import java.util.Map;

/** paged-v2 GET /plugins 响应。 */
public record PagedCatalogDocument(String generation, List<PluginCatalogEntry> items, String nextCursor,
                                   Long totalApproximate, Map<String, Long> facets) { }
