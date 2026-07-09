package top.sywyar.pixivdownload.core.gallery.runtime;

import top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWork;

import java.util.List;
import java.util.Optional;

public record GalleryWorkResult(Optional<GalleryWork> work, List<GalleryDiagnostic> diagnostics) {
    public GalleryWorkResult {
        work = work == null ? Optional.empty() : work;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
