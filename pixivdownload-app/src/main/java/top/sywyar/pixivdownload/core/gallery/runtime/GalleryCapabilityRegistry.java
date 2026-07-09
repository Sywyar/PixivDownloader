package top.sywyar.pixivdownload.core.gallery.runtime;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.gallery.GalleryProjectionProvider;
import top.sywyar.pixivdownload.core.gallery.GalleryWorkProvider;
import top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Atomic owner-scoped registry for projection and work providers. */
@Component
public class GalleryCapabilityRegistry {

    private static final String CORE_OWNER = "core";
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    public record RegisteredProjectionProvider(
            String providerId,
            GalleryProjectionProvider provider,
            List<GalleryProjectionDescriptor> descriptors
    ) {
        public RegisteredProjectionProvider {
            descriptors = List.copyOf(descriptors);
        }
    }

    public record RegisteredWorkProvider(
            String providerId,
            GalleryWorkProvider provider,
            List<GalleryWorkDescriptor> descriptors
    ) {
        public RegisteredWorkProvider {
            descriptors = List.copyOf(descriptors);
        }
    }

    public record Snapshot(
            List<RegisteredProjectionProvider> projectionProviders,
            List<RegisteredWorkProvider> workProviders,
            List<GalleryProjectionDescriptor> projections,
            List<GalleryWorkDescriptor> works,
            List<GalleryDiagnostic> diagnostics
    ) {
        public Snapshot {
            projectionProviders = List.copyOf(projectionProviders);
            workProviders = List.copyOf(workProviders);
            projections = List.copyOf(projections);
            works = List.copyOf(works);
            diagnostics = List.copyOf(diagnostics);
        }

        static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    private record OwnerProviders(
            List<GalleryProjectionProvider> projections,
            List<GalleryWorkProvider> works
    ) {
        OwnerProviders {
            projections = projections == null ? List.of() : List.copyOf(projections);
            works = works == null ? List.of() : List.copyOf(works);
        }
    }

    private record ProjectionRoute(String sourceId, GalleryKind kind) {
    }

    private record WorkRoute(String sourceId, String namespace) {
    }

    private final Object lock = new Object();
    private final Map<String, OwnerProviders> byOwner = new LinkedHashMap<>();
    private volatile Snapshot snapshot = Snapshot.empty();

    public GalleryCapabilityRegistry(List<GalleryProjectionProvider> projections,
                                     List<GalleryWorkProvider> works) {
        if ((projections != null && !projections.isEmpty()) || (works != null && !works.isEmpty())) {
            register(CORE_OWNER, projections, works);
        }
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public void register(String ownerPluginId,
                         List<GalleryProjectionProvider> projections,
                         List<GalleryWorkProvider> works) {
        String owner = requireId(ownerPluginId, "gallery owner plugin id");
        synchronized (lock) {
            Map<String, OwnerProviders> next = new LinkedHashMap<>(byOwner);
            if ((projections == null || projections.isEmpty()) && (works == null || works.isEmpty())) {
                next.remove(owner);
            } else {
                next.put(owner, new OwnerProviders(projections, works));
            }
            Snapshot rebuilt = rebuild(next);
            byOwner.clear();
            byOwner.putAll(next);
            snapshot = rebuilt;
        }
    }

    public void unregister(String ownerPluginId) {
        String owner = requireId(ownerPluginId, "gallery owner plugin id");
        synchronized (lock) {
            if (!byOwner.containsKey(owner)) {
                return;
            }
            Map<String, OwnerProviders> next = new LinkedHashMap<>(byOwner);
            next.remove(owner);
            Snapshot rebuilt = rebuild(next);
            byOwner.clear();
            byOwner.putAll(next);
            snapshot = rebuilt;
        }
    }

    public List<RegisteredProjectionProvider> resolveProjections(GalleryKind kind, String sourceId) {
        return snapshot.projectionProviders().stream()
                .filter(registered -> registered.descriptors().stream().anyMatch(descriptor ->
                        descriptor.kind() == kind
                                && (sourceId == null || sourceId.equals(descriptor.sourceId()))))
                .toList();
    }

    public Optional<RegisteredWorkProvider> resolveWork(String sourceId, String namespace) {
        return resolveWork(sourceId, namespace, Set.of(GalleryDataAccess.SHARED));
    }

    public Optional<RegisteredWorkProvider> resolveWork(String sourceId, String namespace,
                                                        Set<GalleryDataAccess> allowedAccess) {
        Set<GalleryDataAccess> allowed = allowedAccess == null ? Set.of() : Set.copyOf(allowedAccess);
        return snapshot.workProviders().stream()
                .filter(registered -> registered.descriptors().stream().anyMatch(descriptor ->
                        descriptor.sourceId().equals(sourceId)
                                && descriptor.sourceWorkNamespace().equals(namespace)
                                && allowed.contains(descriptor.dataAccess())))
                .findFirst();
    }

    private static Snapshot rebuild(Map<String, OwnerProviders> owners) {
        List<RegisteredProjectionProvider> projections = new ArrayList<>();
        List<RegisteredWorkProvider> works = new ArrayList<>();
        List<GalleryProjectionDescriptor> descriptors = new ArrayList<>();
        List<GalleryWorkDescriptor> workDescriptors = new ArrayList<>();
        List<GalleryDiagnostic> diagnostics = new ArrayList<>();
        Map<ProjectionRoute, String> projectionOwners = new HashMap<>();
        Map<WorkRoute, String> workOwners = new HashMap<>();
        Set<String> projectionProviderIds = new HashSet<>();
        Set<String> workProviderIds = new HashSet<>();

        for (OwnerProviders owner : owners.values()) {
            for (GalleryProjectionProvider provider : owner.projections()) {
                String providerId = providerId(provider, diagnostics);
                if (providerId == null) {
                    continue;
                }
                requireId(providerId, "gallery projection provider id");
                if (!projectionProviderIds.add(providerId)) {
                    throw new IllegalStateException("duplicate gallery projection provider id: " + providerId);
                }
                List<GalleryProjectionDescriptor> declared = projectionDescriptors(provider, providerId, diagnostics);
                if (declared == null) {
                    continue;
                }
                for (GalleryProjectionDescriptor descriptor : declared) {
                    if (descriptor == null) {
                        throw new IllegalStateException("null gallery projection descriptor: " + providerId);
                    }
                    requireId(descriptor.sourceId(), "gallery source id");
                    ProjectionRoute route = new ProjectionRoute(descriptor.sourceId(), descriptor.kind());
                    String previous = projectionOwners.putIfAbsent(route, providerId);
                    if (previous != null) {
                        throw new IllegalStateException("duplicate gallery projection route: "
                                + route.sourceId() + "/" + route.kind() + " (" + previous + " vs " + providerId + ")");
                    }
                    descriptors.add(descriptor);
                }
                projections.add(new RegisteredProjectionProvider(providerId, provider, declared));
            }
            for (GalleryWorkProvider provider : owner.works()) {
                String providerId = providerId(provider, diagnostics);
                if (providerId == null) {
                    continue;
                }
                requireId(providerId, "gallery work provider id");
                if (!workProviderIds.add(providerId)) {
                    throw new IllegalStateException("duplicate gallery work provider id: " + providerId);
                }
                List<GalleryWorkDescriptor> declared = workDescriptors(provider, providerId, diagnostics);
                if (declared == null) {
                    continue;
                }
                for (GalleryWorkDescriptor descriptor : declared) {
                    if (descriptor == null) {
                        throw new IllegalStateException("null gallery work descriptor: " + providerId);
                    }
                    requireId(descriptor.sourceId(), "gallery source id");
                    requireId(descriptor.sourceWorkNamespace(), "gallery work namespace");
                    WorkRoute route = new WorkRoute(descriptor.sourceId(), descriptor.sourceWorkNamespace());
                    String previous = workOwners.putIfAbsent(route, providerId);
                    if (previous != null) {
                        throw new IllegalStateException("duplicate gallery work route: "
                                + route.sourceId() + "/" + route.namespace() + " (" + previous + " vs " + providerId + ")");
                    }
                    workDescriptors.add(descriptor);
                }
                works.add(new RegisteredWorkProvider(providerId, provider, declared));
            }
        }
        return new Snapshot(projections, works, descriptors, workDescriptors, diagnostics);
    }

    private static String providerId(GalleryProjectionProvider provider, List<GalleryDiagnostic> diagnostics) {
        if (provider == null) {
            throw new IllegalStateException("null gallery projection provider");
        }
        try {
            return provider.providerId();
        } catch (RuntimeException failure) {
            diagnostics.add(diagnostic(null, "projection-provider-id-failed", failure));
            return null;
        }
    }

    private static String providerId(GalleryWorkProvider provider, List<GalleryDiagnostic> diagnostics) {
        if (provider == null) {
            throw new IllegalStateException("null gallery work provider");
        }
        try {
            return provider.providerId();
        } catch (RuntimeException failure) {
            diagnostics.add(diagnostic(null, "work-provider-id-failed", failure));
            return null;
        }
    }

    private static List<GalleryProjectionDescriptor> projectionDescriptors(
            GalleryProjectionProvider provider, String providerId, List<GalleryDiagnostic> diagnostics) {
        try {
            List<GalleryProjectionDescriptor> declared = provider.projections();
            if (declared == null) {
                diagnostics.add(new GalleryDiagnostic(providerId, null, null,
                        "projection-descriptors-null", "Gallery projection provider returned null descriptors"));
                return null;
            }
            return List.copyOf(declared);
        } catch (RuntimeException failure) {
            diagnostics.add(diagnostic(providerId, "projection-descriptors-failed", failure));
            return null;
        }
    }

    private static List<GalleryWorkDescriptor> workDescriptors(
            GalleryWorkProvider provider, String providerId, List<GalleryDiagnostic> diagnostics) {
        try {
            List<GalleryWorkDescriptor> declared = provider.works();
            if (declared == null) {
                diagnostics.add(new GalleryDiagnostic(providerId, null, null,
                        "work-descriptors-null", "Gallery work provider returned null descriptors"));
                return null;
            }
            return List.copyOf(declared);
        } catch (RuntimeException failure) {
            diagnostics.add(diagnostic(providerId, "work-descriptors-failed", failure));
            return null;
        }
    }

    private static String requireId(String value, String label) {
        if (value == null || !ID_PATTERN.matcher(value.trim()).matches()) {
            throw new IllegalStateException("invalid " + label + ": " + value);
        }
        return value.trim();
    }

    private static GalleryDiagnostic diagnostic(String providerId, String code, RuntimeException failure) {
        String message = failure.getClass().getSimpleName();
        if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
            message += ": " + failure.getMessage();
        }
        return new GalleryDiagnostic(providerId, null, null, code, message);
    }
}
