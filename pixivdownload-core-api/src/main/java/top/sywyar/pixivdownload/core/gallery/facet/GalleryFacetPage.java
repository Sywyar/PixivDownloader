package top.sywyar.pixivdownload.core.gallery.facet;

import top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic;

import java.util.List;

public record GalleryFacetPage(
        List<GalleryFacet> facets,
        List<GalleryDiagnostic> diagnostics
) {

    public GalleryFacetPage {
        facets = facets == null ? List.of() : List.copyOf(facets);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static GalleryFacetPage empty() {
        return new GalleryFacetPage(List.of(), List.of());
    }

    public static GalleryFacetPage empty(List<GalleryDiagnostic> diagnostics) {
        return new GalleryFacetPage(List.of(), diagnostics);
    }
}
