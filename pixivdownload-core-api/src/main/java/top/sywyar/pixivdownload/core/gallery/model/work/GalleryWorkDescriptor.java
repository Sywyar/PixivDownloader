package top.sywyar.pixivdownload.core.gallery.model.work;

import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;

import java.util.Objects;

/** Registration descriptor for a source-owned work namespace. */
public record GalleryWorkDescriptor(String sourceId, String sourceWorkNamespace, GalleryDataAccess dataAccess) {
    public GalleryWorkDescriptor {
        sourceId = requireText(sourceId, "sourceId");
        sourceWorkNamespace = requireText(sourceWorkNamespace, "sourceWorkNamespace");
        Objects.requireNonNull(dataAccess, "dataAccess");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
