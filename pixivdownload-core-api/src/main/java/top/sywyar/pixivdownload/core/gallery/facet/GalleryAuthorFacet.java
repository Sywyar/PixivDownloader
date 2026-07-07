package top.sywyar.pixivdownload.core.gallery.facet;

public record GalleryAuthorFacet(
        String sourceId,
        String authorId,
        String displayName,
        long count
) implements GalleryFacet {

    public GalleryAuthorFacet {
        sourceId = blankToNull(sourceId);
        authorId = blankToNull(authorId);
        displayName = blankToNull(displayName);
        count = Math.max(0, count);
    }

    @Override
    public GalleryFacetType type() {
        return GalleryFacetType.AUTHOR;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
