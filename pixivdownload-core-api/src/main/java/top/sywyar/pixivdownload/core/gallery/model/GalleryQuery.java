package top.sywyar.pixivdownload.core.gallery.model;

import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetFilter;

import java.util.List;
import java.util.Map;

public record GalleryQuery(
        GalleryKind kind,
        String sourceId,
        List<GalleryFacetFilter> facetFilters,
        Map<String, String> filters,
        int offset,
        int limit
) {

    public GalleryQuery {
        sourceId = blankToNull(sourceId);
        facetFilters = facetFilters == null ? List.of() : List.copyOf(facetFilters);
        filters = filters == null ? Map.of() : Map.copyOf(filters);
        offset = Math.max(0, offset);
        limit = limit <= 0 ? 50 : limit;
    }

    public static GalleryQuery of(GalleryKind kind) {
        return new GalleryQuery(kind, null, List.of(), Map.of(), 0, 50);
    }

    public static GalleryQuery of(GalleryKind kind, String sourceId) {
        return new GalleryQuery(kind, sourceId, List.of(), Map.of(), 0, 50);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
