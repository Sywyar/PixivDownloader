package top.sywyar.pixivdownload.core.gallery.query;

import java.util.Objects;
import java.util.Set;

/** Typed filter with an optional source scope for author and tag identities. */
public record GalleryFilter(
        GalleryFilterField field,
        GalleryFilterMode mode,
        String sourceId,
        Set<String> values
) {

    public GalleryFilter {
        Objects.requireNonNull(field, "field");
        mode = mode == null ? GalleryFilterMode.ANY_OF : mode;
        sourceId = sourceId == null || sourceId.isBlank() ? null : sourceId.trim();
        values = values == null ? Set.of() : Set.copyOf(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("filter values must not be empty");
        }
    }
}
