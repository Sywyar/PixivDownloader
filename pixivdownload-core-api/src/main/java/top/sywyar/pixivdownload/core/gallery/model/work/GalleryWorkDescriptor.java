package top.sywyar.pixivdownload.core.gallery.model.work;

import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;

/** Registration descriptor for a source-owned work namespace. */
public record GalleryWorkDescriptor(String sourceId, String sourceWorkNamespace, GalleryDataAccess dataAccess) {

    public GalleryWorkDescriptor(String sourceId, String sourceWorkNamespace) {
        this(sourceId, sourceWorkNamespace, GalleryDataAccess.SHARED);
    }

    public GalleryWorkDescriptor {
        sourceId = requireText(sourceId, "sourceId");
        sourceWorkNamespace = requireText(sourceWorkNamespace, "sourceWorkNamespace");
        dataAccess = dataAccess == null ? GalleryDataAccess.SHARED : dataAccess;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
