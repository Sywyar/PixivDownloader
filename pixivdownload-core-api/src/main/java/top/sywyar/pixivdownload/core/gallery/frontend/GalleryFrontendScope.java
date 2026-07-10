package top.sywyar.pixivdownload.core.gallery.frontend;

import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Typed applicability scope for one gallery frontend contribution.
 * Empty sets are wildcards, values inside one dimension are alternatives, and dimensions combine with AND.
 */
public record GalleryFrontendScope(
        Set<String> sourceIds,
        Set<String> sourceWorkNamespaces,
        Set<GalleryKind> galleryKinds,
        Set<GalleryMediaKind> mediaKinds
) {

    public GalleryFrontendScope {
        sourceIds = immutableTextSet(sourceIds, "sourceIds");
        sourceWorkNamespaces = immutableTextSet(sourceWorkNamespaces, "sourceWorkNamespaces");
        galleryKinds = immutableSet(galleryKinds, "galleryKinds");
        mediaKinds = immutableSet(mediaKinds, "mediaKinds");
    }

    public static GalleryFrontendScope any() {
        return new GalleryFrontendScope(Set.of(), Set.of(), Set.of(), Set.of());
    }

    public boolean matches(String sourceId, String sourceWorkNamespace,
                           GalleryKind galleryKind, GalleryMediaKind mediaKind) {
        return matches(sourceIds, sourceId)
                && matches(sourceWorkNamespaces, sourceWorkNamespace)
                && matches(galleryKinds, galleryKind)
                && matches(mediaKinds, mediaKind);
    }

    /** Returns true when at least one concrete identity can satisfy both scopes. */
    public boolean overlaps(GalleryFrontendScope other) {
        Objects.requireNonNull(other, "other");
        return overlaps(sourceIds, other.sourceIds)
                && overlaps(sourceWorkNamespaces, other.sourceWorkNamespaces)
                && overlaps(galleryKinds, other.galleryKinds)
                && overlaps(mediaKinds, other.mediaKinds);
    }

    private static Set<String> immutableTextSet(Set<String> values, String field) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not contain blank values");
            }
            normalized.add(value.trim());
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static <T> Set<T> immutableSet(Set<T> values, String field) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<T> copy = new LinkedHashSet<>();
        for (T value : values) {
            if (value == null) {
                throw new IllegalArgumentException(field + " must not contain null values");
            }
            copy.add(value);
        }
        return Collections.unmodifiableSet(copy);
    }

    private static <T> boolean matches(Set<T> expected, T actual) {
        return expected.isEmpty() || actual != null && expected.contains(actual);
    }

    private static <T> boolean overlaps(Set<T> left, Set<T> right) {
        return left.isEmpty() || right.isEmpty() || left.stream().anyMatch(right::contains);
    }
}
