package top.sywyar.pixivdownload.core.gallery.model.projection;

import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilterCapability;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilterField;

import java.util.Map;
import java.util.Objects;

/** Registration descriptor for one source and list kind. */
public record GalleryProjectionDescriptor(
        String sourceId,
        GalleryKind kind,
        String displayNamespace,
        String displayI18nKey,
        int order,
        GalleryDataAccess dataAccess,
        Map<GalleryFilterField, GalleryFilterCapability> filterCapabilities
) {

    public GalleryProjectionDescriptor(String sourceId, GalleryKind kind, String displayNamespace,
                                       String displayI18nKey, int order,
                                       Map<GalleryFilterField, GalleryFilterCapability> filterCapabilities) {
        this(sourceId, kind, displayNamespace, displayI18nKey, order,
                GalleryDataAccess.SHARED, filterCapabilities);
    }

    public GalleryProjectionDescriptor {
        sourceId = requireText(sourceId, "sourceId");
        Objects.requireNonNull(kind, "kind");
        displayNamespace = blankToNull(displayNamespace);
        displayI18nKey = blankToNull(displayI18nKey);
        dataAccess = dataAccess == null ? GalleryDataAccess.SHARED : dataAccess;
        filterCapabilities = filterCapabilities == null ? Map.of() : Map.copyOf(filterCapabilities);
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
