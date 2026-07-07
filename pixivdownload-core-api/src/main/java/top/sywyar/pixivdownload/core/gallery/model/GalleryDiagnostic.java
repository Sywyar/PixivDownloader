package top.sywyar.pixivdownload.core.gallery.model;

public record GalleryDiagnostic(
        String providerId,
        String sourceId,
        GalleryKind kind,
        String code,
        String message
) {

    public GalleryDiagnostic {
        providerId = blankToNull(providerId);
        sourceId = blankToNull(sourceId);
        code = blankToNull(code);
        message = blankToNull(message);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
