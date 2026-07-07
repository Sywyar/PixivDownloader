package top.sywyar.pixivdownload.core.gallery.facet;

public record GalleryTagFacet(
        String sourceId,
        String tagId,
        String name,
        String translatedName,
        long count
) implements GalleryFacet {

    public GalleryTagFacet {
        sourceId = blankToNull(sourceId);
        tagId = blankToNull(tagId);
        name = blankToNull(name);
        translatedName = blankToNull(translatedName);
        count = Math.max(0, count);
    }

    @Override
    public GalleryFacetType type() {
        return GalleryFacetType.TAG;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
