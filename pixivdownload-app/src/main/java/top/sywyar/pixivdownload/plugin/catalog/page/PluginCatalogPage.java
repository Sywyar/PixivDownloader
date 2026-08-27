package top.sywyar.pixivdownload.plugin.catalog.page;

import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogEntry;

import java.util.List;
import java.util.Map;

/** manifest-v1 与 paged-v2 共用的有界页。 */
public record PluginCatalogPage(String generation, List<PluginCatalogEntry> items, String nextCursor,
                                Long totalApproximate, Map<String, Long> facets, boolean stale) {
    public PluginCatalogPage {
        items = List.copyOf(items == null ? List.of() : items);
        facets = Map.copyOf(facets == null ? Map.of() : facets);
    }

    public PluginCatalogPage staleCopy() {
        return new PluginCatalogPage(generation, items, nextCursor, totalApproximate, facets, true);
    }
}
