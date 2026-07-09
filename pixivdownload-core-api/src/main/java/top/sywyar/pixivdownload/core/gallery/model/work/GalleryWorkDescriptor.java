package top.sywyar.pixivdownload.core.gallery.model.work;

/** Registration descriptor for a source-owned work namespace. */
public record GalleryWorkDescriptor(String sourceId, String sourceWorkNamespace) {

    public GalleryWorkDescriptor {
        sourceId = requireText(sourceId, "sourceId");
        sourceWorkNamespace = requireText(sourceWorkNamespace, "sourceWorkNamespace");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
