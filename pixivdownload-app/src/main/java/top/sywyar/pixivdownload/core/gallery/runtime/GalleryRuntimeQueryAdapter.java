package top.sywyar.pixivdownload.core.gallery.runtime;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendContribution;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendScope;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** App implementation of the stable read-only gallery runtime contract. */
@Component
public class GalleryRuntimeQueryAdapter implements GalleryRuntimeQuery {

    private final GalleryCapabilityRegistry registry;
    private final GalleryProjectionBroker projectionBroker;
    private final GalleryWorkBroker workBroker;

    public GalleryRuntimeQueryAdapter(GalleryCapabilityRegistry registry,
                                      GalleryProjectionBroker projectionBroker,
                                      GalleryWorkBroker workBroker) {
        this.registry = registry;
        this.projectionBroker = projectionBroker;
        this.workBroker = workBroker;
    }

    @Override
    public GalleryRuntimeSnapshot snapshot(Set<GalleryDataAccess> allowedAccess) {
        Set<GalleryDataAccess> allowed = immutableAccess(allowedAccess);
        GalleryCapabilityRegistry.Snapshot snapshot = registry.snapshot();
        List<GalleryProjectionDescriptor> projections = snapshot.projections().stream()
                .filter(descriptor -> allowed.contains(descriptor.dataAccess()))
                .toList();
        List<GalleryWorkDescriptor> works = snapshot.works().stream()
                .filter(descriptor -> allowed.contains(descriptor.dataAccess()))
                .toList();
        List<GalleryFrontendContribution> frontends = visibleFrontends(snapshot, allowed);
        return new GalleryRuntimeSnapshot(
                snapshot.generation(),
                projections,
                works,
                frontends,
                allowed.contains(GalleryDataAccess.ADMIN_ONLY) ? snapshot.diagnostics() : List.of());
    }

    @Override
    public GalleryProjectionPage page(GalleryProjectionQuery query, Set<GalleryDataAccess> allowedAccess) {
        return projectionBroker.page(query, immutableAccess(allowedAccess));
    }

    @Override
    public GalleryCountResult count(GalleryProjectionQuery query, Set<GalleryDataAccess> allowedAccess) {
        return projectionBroker.count(query, immutableAccess(allowedAccess));
    }

    @Override
    public GalleryFacetPage facets(GalleryProjectionQuery query, Set<GalleryDataAccess> allowedAccess) {
        return projectionBroker.facets(query, immutableAccess(allowedAccess));
    }

    @Override
    public GalleryWorkResult findWork(GalleryWorkKey key, Set<GalleryDataAccess> allowedAccess) {
        return workBroker.find(key, immutableAccess(allowedAccess));
    }

    private static List<GalleryFrontendContribution> visibleFrontends(
            GalleryCapabilityRegistry.Snapshot snapshot,
            Set<GalleryDataAccess> allowedAccess) {
        return snapshot.frontendContributions().stream()
                .filter(frontend -> isFrontendVisible(frontend, snapshot, allowedAccess))
                .map(GalleryCapabilityRegistry.RegisteredFrontendContribution::contribution)
                .toList();
    }

    private static boolean isFrontendVisible(
            GalleryCapabilityRegistry.RegisteredFrontendContribution frontend,
            GalleryCapabilityRegistry.Snapshot snapshot,
            Set<GalleryDataAccess> allowedAccess) {
        String owner = frontend.ownerPluginId();
        GalleryFrontendScope scope = frontend.contribution().scope();
        List<GalleryProjectionDescriptor> projections = snapshot.projectionProviders().stream()
                .filter(provider -> owner.equals(provider.ownerPluginId()))
                .flatMap(provider -> provider.descriptors().stream())
                .filter(descriptor -> matches(scope.sourceIds(), descriptor.sourceId()))
                .filter(descriptor -> matches(scope.galleryKinds(), descriptor.kind()))
                .toList();
        List<GalleryWorkDescriptor> works = snapshot.workProviders().stream()
                .filter(provider -> owner.equals(provider.ownerPluginId()))
                .flatMap(provider -> provider.descriptors().stream())
                .filter(descriptor -> matches(scope.sourceIds(), descriptor.sourceId()))
                .filter(descriptor -> matches(
                        scope.sourceWorkNamespaces(), descriptor.sourceWorkNamespace()))
                .toList();

        if (!provesScope(scope, projections, works)) {
            return false;
        }
        Set<GalleryDataAccess> authoritativeAccess = new LinkedHashSet<>();
        projections.forEach(descriptor -> authoritativeAccess.add(descriptor.dataAccess()));
        works.forEach(descriptor -> authoritativeAccess.add(descriptor.dataAccess()));
        return !authoritativeAccess.isEmpty() && allowedAccess.containsAll(authoritativeAccess);
    }

    private static boolean provesScope(
            GalleryFrontendScope scope,
            List<GalleryProjectionDescriptor> projections,
            List<GalleryWorkDescriptor> works) {
        if (!scope.galleryKinds().isEmpty()) {
            for (GalleryKind kind : scope.galleryKinds()) {
                if (scope.sourceIds().isEmpty()) {
                    if (projections.stream().noneMatch(descriptor -> descriptor.kind() == kind)) {
                        return false;
                    }
                } else {
                    for (String sourceId : scope.sourceIds()) {
                        if (projections.stream().noneMatch(descriptor ->
                                descriptor.sourceId().equals(sourceId) && descriptor.kind() == kind)) {
                            return false;
                        }
                    }
                }
            }
        }
        if (!scope.sourceWorkNamespaces().isEmpty()) {
            for (String namespace : scope.sourceWorkNamespaces()) {
                if (scope.sourceIds().isEmpty()) {
                    if (works.stream().noneMatch(descriptor ->
                            descriptor.sourceWorkNamespace().equals(namespace))) {
                        return false;
                    }
                } else {
                    for (String sourceId : scope.sourceIds()) {
                        if (works.stream().noneMatch(descriptor ->
                                descriptor.sourceId().equals(sourceId)
                                        && descriptor.sourceWorkNamespace().equals(namespace))) {
                            return false;
                        }
                    }
                }
            }
        }
        if (scope.galleryKinds().isEmpty() && scope.sourceWorkNamespaces().isEmpty()
                && !scope.sourceIds().isEmpty()) {
            for (String sourceId : scope.sourceIds()) {
                boolean projected = projections.stream()
                        .anyMatch(descriptor -> descriptor.sourceId().equals(sourceId));
                boolean resolved = works.stream()
                        .anyMatch(descriptor -> descriptor.sourceId().equals(sourceId));
                if (!projected && !resolved) {
                    return false;
                }
            }
        }
        return !projections.isEmpty() || !works.isEmpty();
    }

    private static Set<GalleryDataAccess> immutableAccess(Set<GalleryDataAccess> allowedAccess) {
        return allowedAccess == null ? Set.of() : Set.copyOf(allowedAccess);
    }

    private static <T> boolean matches(Set<T> expected, T actual) {
        return expected.isEmpty() || expected.contains(actual);
    }
}
