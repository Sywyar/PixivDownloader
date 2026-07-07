package top.sywyar.pixivdownload.core.gallery.model;

import java.util.List;

public record GalleryPage(
        List<GalleryItem> items,
        long total,
        boolean hasMore,
        int offset,
        int limit,
        List<GalleryDiagnostic> diagnostics
) {

    public GalleryPage {
        items = items == null ? List.of() : List.copyOf(items);
        total = Math.max(0, total);
        offset = Math.max(0, offset);
        limit = Math.max(0, limit);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static GalleryPage empty(int offset, int limit, List<GalleryDiagnostic> diagnostics) {
        return new GalleryPage(List.of(), 0, false, offset, limit, diagnostics);
    }
}
