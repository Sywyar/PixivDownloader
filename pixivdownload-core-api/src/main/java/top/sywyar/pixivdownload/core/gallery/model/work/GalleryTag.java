package top.sywyar.pixivdownload.core.gallery.model.work;

/** Source-scoped tag; equal labels from different sources remain distinct. */
public record GalleryTag(String sourceId, String tagId, String label) {

    public GalleryTag {
        sourceId = requireText(sourceId, "sourceId");
        tagId = requireText(tagId, "tagId");
        label = blankToNull(label);
    }

    private static String requireText(String value, String field) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
