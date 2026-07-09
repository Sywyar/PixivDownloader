package top.sywyar.pixivdownload.core.gallery.runtime;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;

import java.util.ArrayList;
import java.util.List;

/** Resolves complete work details by source and namespace without list-kind coupling. */
@Component
public class GalleryWorkBroker {

    private final GalleryCapabilityRegistry registry;

    public GalleryWorkBroker(GalleryCapabilityRegistry registry) {
        this.registry = registry;
    }

    public GalleryWorkResult find(GalleryWorkKey key) {
        List<GalleryDiagnostic> diagnostics = new ArrayList<>(registry.snapshot().diagnostics());
        var registered = registry.resolveWork(key.sourceId(), key.sourceWorkNamespace());
        if (registered.isEmpty()) {
            return new GalleryWorkResult(java.util.Optional.empty(), diagnostics);
        }
        try {
            var work = registered.get().provider().find(key);
            if (work == null) {
                diagnostics.add(new GalleryDiagnostic(registered.get().providerId(), key.sourceId(), null,
                        "gallery-work-null-result", "Gallery work provider returned null Optional"));
                return new GalleryWorkResult(java.util.Optional.empty(), diagnostics);
            }
            work.filter(value -> !key.equals(value.key())).ifPresent(value -> diagnostics.add(
                    new GalleryDiagnostic(registered.get().providerId(), key.sourceId(), null,
                            "gallery-work-identity-mismatch", "Gallery work provider returned another identity")));
            return work.filter(value -> key.equals(value.key())).map(value ->
                    new GalleryWorkResult(java.util.Optional.of(value), diagnostics))
                    .orElseGet(() -> new GalleryWorkResult(java.util.Optional.empty(), diagnostics));
        } catch (RuntimeException failure) {
            diagnostics.add(new GalleryDiagnostic(registered.get().providerId(), key.sourceId(), null,
                    "gallery-work-query-failed", failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage())));
            return new GalleryWorkResult(java.util.Optional.empty(), diagnostics);
        }
    }
}
