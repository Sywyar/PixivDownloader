package top.sywyar.pixivdownload.core.gallery.model;

public record GalleryWorkRef(
        String providerId,
        String sourceId,
        GalleryKind kind,
        String workId
) {

    public GalleryWorkRef {
        providerId = blankToNull(providerId);
        sourceId = blankToNull(sourceId);
        workId = blankToNull(workId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
