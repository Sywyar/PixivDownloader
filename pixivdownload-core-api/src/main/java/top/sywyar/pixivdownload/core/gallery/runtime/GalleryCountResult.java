package top.sywyar.pixivdownload.core.gallery.runtime;

import top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic;

import java.util.List;

public record GalleryCountResult(long count, List<GalleryDiagnostic> diagnostics) {
    public GalleryCountResult {
        count = Math.max(0, count);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
