package top.sywyar.pixivdownload.core.gallery.query;

import top.sywyar.pixivdownload.core.gallery.model.GalleryFieldCapability;

import java.util.Objects;
import java.util.Set;

/** Capability policy for one typed filter field. */
public record GalleryFilterCapability(
        GalleryFieldCapability capability,
        Set<String> constantValues
) {

    public GalleryFilterCapability {
        Objects.requireNonNull(capability, "capability");
        constantValues = constantValues == null ? Set.of() : Set.copyOf(constantValues);
        if (capability == GalleryFieldCapability.CONSTANT && constantValues.isEmpty()) {
            throw new IllegalArgumentException("constant filter capability requires values");
        }
        if (capability != GalleryFieldCapability.CONSTANT && !constantValues.isEmpty()) {
            throw new IllegalArgumentException("only constant filter capability may declare values");
        }
    }

    public static GalleryFilterCapability supported() {
        return new GalleryFilterCapability(GalleryFieldCapability.SUPPORTED, Set.of());
    }

    public static GalleryFilterCapability constant(String value) {
        return new GalleryFilterCapability(GalleryFieldCapability.CONSTANT, Set.of(value));
    }

    public static GalleryFilterCapability unknown() {
        return new GalleryFilterCapability(GalleryFieldCapability.UNKNOWN, Set.of());
    }

    public static GalleryFilterCapability unsupported() {
        return new GalleryFilterCapability(GalleryFieldCapability.UNSUPPORTED, Set.of());
    }
}
