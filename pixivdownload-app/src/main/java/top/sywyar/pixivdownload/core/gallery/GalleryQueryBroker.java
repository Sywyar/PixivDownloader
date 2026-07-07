package top.sywyar.pixivdownload.core.gallery;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacet;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic;
import top.sywyar.pixivdownload.core.gallery.model.GalleryPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class GalleryQueryBroker {

    private final GalleryProviderRegistry providerRegistry;

    public GalleryQueryBroker(GalleryProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public GalleryProviderRegistry.Snapshot snapshot() {
        return providerRegistry.snapshot();
    }

    public GalleryPage query(GalleryQuery query) {
        if (query == null) {
            return GalleryPage.empty(0, 0, List.of(new GalleryDiagnostic(
                    null, null, null, "gallery-query-null", "Gallery query must not be null")));
        }
        List<GalleryDiagnostic> diagnostics = new ArrayList<>(providerRegistry.snapshot().diagnostics());
        List<GalleryProviderRegistry.RegisteredProvider> providers = providersFor(query);
        if (providers.isEmpty()) {
            return GalleryPage.empty(query.offset(), query.limit(), diagnostics);
        }
        if (providers.size() > 1) {
            diagnostics.add(new GalleryDiagnostic(
                    null, query.sourceId(), query.kind(),
                    "gallery-source-required-for-page",
                    "Gallery page query resolved multiple providers; narrow the query to one source"));
            return GalleryPage.empty(query.offset(), query.limit(), diagnostics);
        }

        GalleryProviderRegistry.RegisteredProvider registered = providers.get(0);
        try {
            GalleryPage page = registered.provider().query(query);
            if (page == null) {
                diagnostics.add(new GalleryDiagnostic(
                        registered.providerId(), query.sourceId(), query.kind(),
                        "gallery-query-null-page", "Gallery provider returned null page"));
                return GalleryPage.empty(query.offset(), query.limit(), diagnostics);
            }
            diagnostics.addAll(page.diagnostics());
            return new GalleryPage(page.items(), page.total(), page.hasMore(),
                    query.offset(), query.limit(), diagnostics);
        } catch (RuntimeException e) {
            diagnostics.add(diagnostic(
                    registered.providerId(), query.sourceId(), query.kind(), "gallery-query-failed", e));
            return GalleryPage.empty(query.offset(), query.limit(), diagnostics);
        }
    }

    public GalleryFacetPage facets(GalleryQuery query) {
        if (query == null) {
            return GalleryFacetPage.empty(List.of(new GalleryDiagnostic(
                    null, null, null, "gallery-query-null", "Gallery query must not be null")));
        }
        List<GalleryDiagnostic> diagnostics = new ArrayList<>(providerRegistry.snapshot().diagnostics());
        List<GalleryFacet> facets = new ArrayList<>();
        for (GalleryProviderRegistry.RegisteredProvider registered : providersFor(query)) {
            try {
                GalleryFacetPage page = registered.provider().facets(query);
                if (page == null) {
                    diagnostics.add(new GalleryDiagnostic(
                            registered.providerId(), query.sourceId(), query.kind(),
                            "gallery-facets-null-page", "Gallery provider returned null facet page"));
                    continue;
                }
                facets.addAll(page.facets());
                diagnostics.addAll(page.diagnostics());
            } catch (RuntimeException e) {
                diagnostics.add(diagnostic(
                        registered.providerId(), query.sourceId(), query.kind(), "gallery-facets-failed", e));
            }
        }
        return new GalleryFacetPage(facets, diagnostics);
    }

    private List<GalleryProviderRegistry.RegisteredProvider> providersFor(GalleryQuery query) {
        List<GalleryProviderRegistry.RegisteredProvider> providers =
                providerRegistry.resolve(query.kind(), query.sourceId());
        Set<String> facetSourceIds = query.facetFilters().stream()
                .map(filter -> filter.sourceId())
                .filter(sourceId -> sourceId != null && !sourceId.isBlank())
                .collect(Collectors.toSet());
        if (facetSourceIds.isEmpty()) {
            return providers;
        }
        return providers.stream()
                .filter(provider -> provider.sources().stream()
                        .anyMatch(source -> facetSourceIds.contains(source.sourceId())))
                .toList();
    }

    private static GalleryDiagnostic diagnostic(String providerId,
                                                String sourceId,
                                                top.sywyar.pixivdownload.core.gallery.model.GalleryKind kind,
                                                String code,
                                                RuntimeException e) {
        String message = e.getClass().getSimpleName();
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            message += ": " + e.getMessage();
        }
        return new GalleryDiagnostic(providerId, sourceId, kind, code, message);
    }
}
