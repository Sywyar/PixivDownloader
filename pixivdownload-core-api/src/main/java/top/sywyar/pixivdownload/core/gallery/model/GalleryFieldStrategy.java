package top.sywyar.pixivdownload.core.gallery.model;

public record GalleryFieldStrategy(
        String field,
        GalleryFieldCapability capability,
        String constantValue
) {

    public GalleryFieldStrategy {
        field = blankToNull(field);
        capability = capability == null ? GalleryFieldCapability.UNKNOWN : capability;
        constantValue = blankToNull(constantValue);
    }

    public static GalleryFieldStrategy supported(String field) {
        return new GalleryFieldStrategy(field, GalleryFieldCapability.SUPPORTED, null);
    }

    public static GalleryFieldStrategy constant(String field, String value) {
        return new GalleryFieldStrategy(field, GalleryFieldCapability.CONSTANT, value);
    }

    public static GalleryFieldStrategy unknown(String field) {
        return new GalleryFieldStrategy(field, GalleryFieldCapability.UNKNOWN, null);
    }

    public static GalleryFieldStrategy unsupported(String field) {
        return new GalleryFieldStrategy(field, GalleryFieldCapability.UNSUPPORTED, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
