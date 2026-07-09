package top.sywyar.pixivdownload.core.gallery.model.identity;

/** Stable source-owned identity shared by every projection of one work. */
public record GalleryWorkKey(String sourceId, String sourceWorkNamespace, String sourceWorkId) {

    public GalleryWorkKey {
        sourceId = requireText(sourceId, "sourceId");
        sourceWorkNamespace = requireText(sourceWorkNamespace, "sourceWorkNamespace");
        sourceWorkId = requireText(sourceWorkId, "sourceWorkId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
