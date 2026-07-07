package top.sywyar.pixivdownload.core.gallery.facet;

public record GalleryFacetFilter(
        String sourceId,
        GalleryFacetType type,
        String value
) {

    public GalleryFacetFilter {
        sourceId = blankToNull(sourceId);
        value = blankToNull(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
