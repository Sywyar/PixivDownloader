package top.sywyar.pixivdownload.core.gallery.model.projection;

import top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic;

import java.util.List;

/** Page returned by one provider; cursor is opaque outside that provider. */
public record GalleryProjectionPage(
        List<GalleryProjection> projections,
        String nextCursor,
        boolean hasMore,
        List<GalleryDiagnostic> diagnostics
) {

    public GalleryProjectionPage {
        projections = projections == null ? List.of() : List.copyOf(projections);
        nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor.trim();
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (hasMore && nextCursor == null) {
            throw new IllegalArgumentException("hasMore page must provide nextCursor");
        }
    }

    public static GalleryProjectionPage empty() {
        return new GalleryProjectionPage(List.of(), null, false, List.of());
    }
}
