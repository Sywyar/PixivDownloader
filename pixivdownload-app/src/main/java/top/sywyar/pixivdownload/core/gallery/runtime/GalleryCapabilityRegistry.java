package top.sywyar.pixivdownload.core.gallery.runtime;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.gallery.GalleryProjectionProvider;
import top.sywyar.pixivdownload.core.gallery.GalleryWorkProvider;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendContribution;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendHook;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendScope;
import top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Atomic owner-scoped registry for projection, work and typed frontend gallery capabilities. */
@Component
public class GalleryCapabilityRegistry {

    private static final String CORE_OWNER = "core";
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    public record RegisteredProjectionProvider(
            String ownerPluginId,
            String providerId,
            GalleryProjectionProvider provider,
            List<GalleryProjectionDescriptor> descriptors
    ) {
        public RegisteredProjectionProvider {
            Objects.requireNonNull(ownerPluginId, "ownerPluginId");
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(provider, "provider");
            descriptors = List.copyOf(descriptors);
        }
    }

    public record RegisteredWorkProvider(
            String ownerPluginId,
            String providerId,
            GalleryWorkProvider provider,
            List<GalleryWorkDescriptor> descriptors
    ) {
        public RegisteredWorkProvider {
            Objects.requireNonNull(ownerPluginId, "ownerPluginId");
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(provider, "provider");
            descriptors = List.copyOf(descriptors);
        }
    }

    public record RegisteredFrontendContribution(
            String ownerPluginId,
            GalleryFrontendContribution contribution
    ) {
        public RegisteredFrontendContribution {
            Objects.requireNonNull(ownerPluginId, "ownerPluginId");
            Objects.requireNonNull(contribution, "contribution");
        }
    }

    public record Snapshot(
            long generation,
            List<RegisteredProjectionProvider> projectionProviders,
            List<RegisteredWorkProvider> workProviders,
            List<RegisteredFrontendContribution> frontendContributions,
            List<GalleryProjectionDescriptor> projections,
            List<GalleryWorkDescriptor> works,
            List<GalleryDiagnostic> diagnostics
    ) {
        public Snapshot {
            projectionProviders = List.copyOf(projectionProviders);
            workProviders = List.copyOf(workProviders);
            frontendContributions = List.copyOf(frontendContributions);
            projections = List.copyOf(projections);
            works = List.copyOf(works);
            diagnostics = List.copyOf(diagnostics);
        }

        static Snapshot empty() {
            return new Snapshot(0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    private record OwnerCapabilities(
            List<RegisteredProjectionProvider> projections,
            List<RegisteredWorkProvider> works,
            List<RegisteredFrontendContribution> frontends
    ) {
        OwnerCapabilities {
            projections = List.copyOf(projections);
            works = List.copyOf(works);
            frontends = List.copyOf(frontends);
        }
    }

    private record ProjectionRoute(String sourceId, GalleryKind kind) {
    }

    private record WorkRoute(String sourceId, String namespace) {
    }

    private final Object lock = new Object();
    private final Map<String, OwnerCapabilities> byOwner = new LinkedHashMap<>();
    private volatile Snapshot snapshot = Snapshot.empty();

    public GalleryCapabilityRegistry(List<GalleryProjectionProvider> projections,
                                     List<GalleryWorkProvider> works) {
        if ((projections != null && !projections.isEmpty()) || (works != null && !works.isEmpty())) {
            register(CORE_OWNER, projections, works, List.of());
        }
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public void register(String ownerPluginId,
                         List<GalleryProjectionProvider> projections,
                         List<GalleryWorkProvider> works) {
        register(ownerPluginId, projections, works, List.of());
    }

    /**
     * Replaces every gallery capability of one owner in a single snapshot publication.
     * Descriptor getters and owner-local validation run before the registry lock is mutated, so failure preserves
     * the previous owner generation and every other owner remains untouched.
     */
    public void register(String ownerPluginId,
                         List<GalleryProjectionProvider> projections,
                         List<GalleryWorkProvider> works,
                         List<GalleryFrontendContribution> frontends) {
        String owner = requireId(ownerPluginId, "gallery owner plugin id");
        boolean empty = isEmpty(projections) && isEmpty(works) && isEmpty(frontends);
        OwnerCapabilities prepared = empty ? null : prepareOwner(owner, projections, works, frontends);

        synchronized (lock) {
            if (empty && !byOwner.containsKey(owner)) {
                return;
            }
            Map<String, OwnerCapabilities> next = new LinkedHashMap<>(byOwner);
            if (empty) {
                next.remove(owner);
            } else {
                next.put(owner, prepared);
            }
            Snapshot rebuilt = rebuild(next, snapshot.generation() + 1);
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
            Map<String, OwnerCapabilities> next = new LinkedHashMap<>(byOwner);
            next.remove(owner);
            Snapshot rebuilt = rebuild(next, snapshot.generation() + 1);
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

    public List<RegisteredFrontendContribution> resolveFrontends(
            String sourceId, String namespace, GalleryKind kind, GalleryMediaKind mediaKind) {
        return snapshot.frontendContributions().stream()
                .filter(registered -> registered.contribution()
                        .matches(sourceId, namespace, kind, mediaKind))
                .toList();
    }

    private static OwnerCapabilities prepareOwner(
            String owner,
            List<GalleryProjectionProvider> projectionProviders,
            List<GalleryWorkProvider> workProviders,
            List<GalleryFrontendContribution> frontendContributions) {
        List<RegisteredProjectionProvider> projections = new ArrayList<>();
        if (projectionProviders != null) {
            for (GalleryProjectionProvider provider : projectionProviders) {
                if (provider == null) {
                    throw new IllegalStateException("null gallery projection provider (owner: " + owner + ")");
                }
                String providerId = projectionProviderId(owner, provider);
                List<GalleryProjectionDescriptor> descriptors = projectionDescriptors(owner, providerId, provider);
                projections.add(new RegisteredProjectionProvider(owner, providerId, provider, descriptors));
            }
        }

        List<RegisteredWorkProvider> works = new ArrayList<>();
        if (workProviders != null) {
            for (GalleryWorkProvider provider : workProviders) {
                if (provider == null) {
                    throw new IllegalStateException("null gallery work provider (owner: " + owner + ")");
                }
                String providerId = workProviderId(owner, provider);
                List<GalleryWorkDescriptor> descriptors = workDescriptors(owner, providerId, provider);
                works.add(new RegisteredWorkProvider(owner, providerId, provider, descriptors));
            }
        }

        List<RegisteredFrontendContribution> frontends = new ArrayList<>();
        if (frontendContributions != null) {
            for (GalleryFrontendContribution contribution : frontendContributions) {
                validateFrontendContribution(owner, contribution);
                frontends.add(new RegisteredFrontendContribution(owner, contribution));
            }
        }
        return new OwnerCapabilities(projections, works, frontends);
    }

    private static Snapshot rebuild(Map<String, OwnerCapabilities> owners, long generation) {
        List<RegisteredProjectionProvider> projections = new ArrayList<>();
        List<RegisteredWorkProvider> works = new ArrayList<>();
        List<RegisteredFrontendContribution> frontends = new ArrayList<>();
        List<GalleryProjectionDescriptor> descriptors = new ArrayList<>();
        List<GalleryWorkDescriptor> workDescriptors = new ArrayList<>();
        Map<ProjectionRoute, String> projectionOwners = new HashMap<>();
        Map<WorkRoute, String> workOwners = new HashMap<>();
        Set<String> projectionProviderIds = new HashSet<>();
        Set<String> workProviderIds = new HashSet<>();
        Map<String, String> frontendOwners = new HashMap<>();
        List<RegisteredFrontendContribution> renderers = new ArrayList<>();

        for (OwnerCapabilities owner : owners.values()) {
            for (RegisteredProjectionProvider registered : owner.projections()) {
                if (!projectionProviderIds.add(registered.providerId())) {
                    throw new IllegalStateException(
                            "duplicate gallery projection provider id: " + registered.providerId());
                }
                for (GalleryProjectionDescriptor descriptor : registered.descriptors()) {
                    ProjectionRoute route = new ProjectionRoute(descriptor.sourceId(), descriptor.kind());
                    String previous = projectionOwners.putIfAbsent(route, registered.providerId());
                    if (previous != null) {
                        throw new IllegalStateException("duplicate gallery projection route: "
                                + route.sourceId() + "/" + route.kind() + " ("
                                + previous + " vs " + registered.providerId() + ")");
                    }
                    descriptors.add(descriptor);
                }
                projections.add(registered);
            }

            for (RegisteredWorkProvider registered : owner.works()) {
                if (!workProviderIds.add(registered.providerId())) {
                    throw new IllegalStateException("duplicate gallery work provider id: " + registered.providerId());
                }
                for (GalleryWorkDescriptor descriptor : registered.descriptors()) {
                    WorkRoute route = new WorkRoute(descriptor.sourceId(), descriptor.sourceWorkNamespace());
                    String previous = workOwners.putIfAbsent(route, registered.providerId());
                    if (previous != null) {
                        throw new IllegalStateException("duplicate gallery work route: "
                                + route.sourceId() + "/" + route.namespace() + " ("
                                + previous + " vs " + registered.providerId() + ")");
                    }
                    workDescriptors.add(descriptor);
                }
                works.add(registered);
            }

            for (RegisteredFrontendContribution registered : owner.frontends()) {
                GalleryFrontendContribution contribution = registered.contribution();
                String previous = frontendOwners.putIfAbsent(
                        contribution.contributionId(), registered.ownerPluginId());
                if (previous != null) {
                    throw new IllegalStateException("duplicate gallery frontend contribution id: "
                            + contribution.contributionId() + " (" + previous + " vs "
                            + registered.ownerPluginId() + ")");
                }
                if (contribution.hooks().contains(GalleryFrontendHook.MEDIA_RENDERER)) {
                    for (RegisteredFrontendContribution renderer : renderers) {
                        if (contribution.scope().overlaps(renderer.contribution().scope())) {
                            throw new IllegalStateException("conflicting gallery frontend media renderer match: "
                                    + renderer.contribution().contributionId() + " vs "
                                    + contribution.contributionId());
                        }
                    }
                    renderers.add(registered);
                }
                frontends.add(registered);
            }
        }

        frontends.sort(Comparator
                .comparingInt((RegisteredFrontendContribution item) -> item.contribution().order())
                .thenComparing(item -> item.contribution().contributionId()));
        return new Snapshot(generation, projections, works, frontends,
                descriptors, workDescriptors, List.of());
    }

    private static String projectionProviderId(String owner, GalleryProjectionProvider provider) {
        try {
            return requireId(provider.providerId(), "gallery projection provider id");
        } catch (RuntimeException failure) {
            throw registrationFailure(owner, "projection provider id", failure);
        }
    }

    private static String workProviderId(String owner, GalleryWorkProvider provider) {
        try {
            return requireId(provider.providerId(), "gallery work provider id");
        } catch (RuntimeException failure) {
            throw registrationFailure(owner, "work provider id", failure);
        }
    }

    private static List<GalleryProjectionDescriptor> projectionDescriptors(
            String owner, String providerId, GalleryProjectionProvider provider) {
        try {
            List<GalleryProjectionDescriptor> declared = provider.projections();
            if (declared == null) {
                throw new IllegalStateException("provider returned null");
            }
            List<GalleryProjectionDescriptor> copy = new ArrayList<>();
            for (GalleryProjectionDescriptor descriptor : declared) {
                if (descriptor == null) {
                    throw new IllegalStateException("null gallery projection descriptor: " + providerId);
                }
                requireId(descriptor.sourceId(), "gallery source id");
                Objects.requireNonNull(descriptor.kind(), "gallery projection kind");
                copy.add(descriptor);
            }
            return List.copyOf(copy);
        } catch (RuntimeException failure) {
            throw registrationFailure(owner, "projection descriptors for " + providerId, failure);
        }
    }

    private static List<GalleryWorkDescriptor> workDescriptors(
            String owner, String providerId, GalleryWorkProvider provider) {
        try {
            List<GalleryWorkDescriptor> declared = provider.works();
            if (declared == null) {
                throw new IllegalStateException("provider returned null");
            }
            List<GalleryWorkDescriptor> copy = new ArrayList<>();
            for (GalleryWorkDescriptor descriptor : declared) {
                if (descriptor == null) {
                    throw new IllegalStateException("null gallery work descriptor: " + providerId);
                }
                requireId(descriptor.sourceId(), "gallery source id");
                requireId(descriptor.sourceWorkNamespace(), "gallery work namespace");
                copy.add(descriptor);
            }
            return List.copyOf(copy);
        } catch (RuntimeException failure) {
            throw registrationFailure(owner, "work descriptors for " + providerId, failure);
        }
    }

    private static void validateFrontendContribution(String owner, GalleryFrontendContribution contribution) {
        if (contribution == null) {
            throw new IllegalStateException("null gallery frontend contribution (owner: " + owner + ")");
        }
        if (!isSafeSameOriginAbsolutePath(contribution.moduleUrl())) {
            throw new IllegalStateException("gallery frontend moduleUrl must be a safe same-origin absolute path: "
                    + contribution.moduleUrl() + " (contribution: " + contribution.contributionId()
                    + ", owner: " + owner + ")");
        }
        if (contribution.hooks().contains(GalleryFrontendHook.VIEW_ENTRY)
                && !isSafeSameOriginAbsolutePath(contribution.viewHref())) {
            throw new IllegalStateException("gallery frontend viewHref must be a safe same-origin absolute path: "
                    + contribution.viewHref() + " (contribution: " + contribution.contributionId()
                    + ", owner: " + owner + ")");
        }
        GalleryFrontendScope scope = contribution.scope();
        scope.sourceIds().forEach(value -> requireId(value, "gallery frontend source id"));
        scope.sourceWorkNamespaces().forEach(value -> requireId(value, "gallery frontend work namespace"));
    }

    private static boolean isSafeSameOriginAbsolutePath(String value) {
        if (value == null || value.isBlank() || value.charAt(0) != '/') {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isWhitespace(current) || Character.isISOControl(current) || current == '\\') {
                return false;
            }
        }
        try {
            URI uri = URI.create(value);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null) {
                return false;
            }
            String path = uri.getRawPath();
            if (path == null || path.length() <= 1 || !path.startsWith("/") || path.startsWith("//")
                    || path.contains("//")) {
                return false;
            }
            String lowerPath = path.toLowerCase(Locale.ROOT);
            if (lowerPath.contains("%2e") || lowerPath.contains("%2f")
                    || lowerPath.contains("%5c") || lowerPath.contains("%00")) {
                return false;
            }
            for (String segment : path.split("/", -1)) {
                if (segment.equals(".") || segment.equals("..")) {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static boolean isEmpty(List<?> values) {
        return values == null || values.isEmpty();
    }

    private static String requireId(String value, String label) {
        if (value == null || !ID_PATTERN.matcher(value.trim()).matches()) {
            throw new IllegalStateException("invalid " + label + ": " + value);
        }
        return value.trim();
    }

    private static IllegalStateException registrationFailure(
            String owner, String field, RuntimeException failure) {
        return new IllegalStateException("failed to read gallery " + field + " (owner: " + owner + ")", failure);
    }
}
