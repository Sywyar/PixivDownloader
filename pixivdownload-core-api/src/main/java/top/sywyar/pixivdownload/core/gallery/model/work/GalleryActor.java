package top.sywyar.pixivdownload.core.gallery.model.work;

/** Source-scoped author identity used by both projections and work details. */
public record GalleryActor(String sourceId, String actorId, String name, String avatarUrl) {

    public GalleryActor {
        sourceId = requireText(sourceId, "sourceId");
        actorId = requireText(actorId, "actorId");
        name = blankToNull(name);
        avatarUrl = blankToNull(avatarUrl);
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
