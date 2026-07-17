package top.sywyar.pixivdownload.core.gallery.runtime;

import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendContribution;
import top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor;

import java.util.List;

/** Caller-visible descriptor and frontend snapshot of the gallery runtime. */
public record GalleryRuntimeSnapshot(
        long generation,
        List<GalleryProjectionDescriptor> projections,
        List<GalleryWorkDescriptor> works,
        List<GalleryFrontendContribution> frontends,
        List<GalleryDiagnostic> diagnostics
) {
    public GalleryRuntimeSnapshot {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        projections = projections == null ? List.of() : List.copyOf(projections);
        works = works == null ? List.of() : List.copyOf(works);
        frontends = frontends == null ? List.of() : List.copyOf(frontends);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
