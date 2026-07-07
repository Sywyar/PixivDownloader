package top.sywyar.pixivdownload.core.gallery;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.GallerySourceDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class GalleryProviderRegistry {

    private static final String CORE_OWNER_ID = "core";
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    public record RegisteredProvider(
            String providerId,
            GalleryDataProvider provider,
            List<GallerySourceDescriptor> sources
    ) {
        public RegisteredProvider {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    public record Snapshot(
            List<RegisteredProvider> providers,
            List<GallerySourceDescriptor> sources,
            List<GalleryDiagnostic> diagnostics
    ) {
        public Snapshot {
            providers = providers == null ? List.of() : List.copyOf(providers);
            sources = sources == null ? List.of() : List.copyOf(sources);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    private final Object lock = new Object();
    private final Map<String, List<GalleryDataProvider>> byOwner = new LinkedHashMap<>();
    private volatile Snapshot snapshot = new Snapshot(List.of(), List.of(), List.of());

    public GalleryProviderRegistry(List<GalleryDataProvider> providers) {
        if (providers != null && !providers.isEmpty()) {
            register(CORE_OWNER_ID, providers);
        }
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public List<RegisteredProvider> resolve(GalleryKind kind, String sourceId) {
        String normalizedSourceId = blankToNull(sourceId);
        Snapshot current = snapshot;
        return current.providers().stream()
                .filter(provider -> provider.sources().stream()
                        .anyMatch(source -> matches(source, kind, normalizedSourceId)))
                .toList();
    }

    public void register(String ownerPluginId, List<GalleryDataProvider> providers) {
        String owner = normalizeOwner(ownerPluginId);
        synchronized (lock) {
            Map<String, List<GalleryDataProvider>> nextByOwner = new LinkedHashMap<>(byOwner);
            if (providers == null || providers.isEmpty()) {
                nextByOwner.remove(owner);
            } else {
                nextByOwner.put(owner, immutableProviderList(providers));
            }
            Snapshot nextSnapshot = rebuild(nextByOwner);
            byOwner.clear();
            byOwner.putAll(nextByOwner);
            snapshot = nextSnapshot;
        }
    }

    public void unregister(String ownerPluginId) {
        String owner = normalizeOwner(ownerPluginId);
        synchronized (lock) {
            if (!byOwner.containsKey(owner)) {
                return;
            }
            Map<String, List<GalleryDataProvider>> nextByOwner = new LinkedHashMap<>(byOwner);
            nextByOwner.remove(owner);
            Snapshot nextSnapshot = rebuild(nextByOwner);
            byOwner.clear();
            byOwner.putAll(nextByOwner);
            snapshot = nextSnapshot;
        }
    }

    private static Snapshot rebuild(Map<String, List<GalleryDataProvider>> byOwner) {
        Map<String, RegisteredProvider> byProviderId = new LinkedHashMap<>();
        Map<SourceKindKey, String> sourceKindOwners = new HashMap<>();
        Set<String> seenProviderIds = new HashSet<>();
        List<GalleryDiagnostic> diagnostics = new ArrayList<>();
        List<GallerySourceDescriptor> allSources = new ArrayList<>();

        for (List<GalleryDataProvider> providers : byOwner.values()) {
            for (GalleryDataProvider provider : providers) {
                if (provider == null) {
                    throw new IllegalStateException("null gallery data provider");
                }
                ProviderIdLookup providerIdLookup = providerId(provider, diagnostics);
                if (!providerIdLookup.available()) {
                    continue;
                }
                String providerId = providerIdLookup.providerId();
                validateId(providerId, "gallery provider id");
                if (!seenProviderIds.add(providerId)) {
                    throw new IllegalStateException("duplicate gallery provider id: " + providerId);
                }

                List<GallerySourceDescriptor> sources = sources(provider, providerId, diagnostics);
                if (sources == null) {
                    continue;
                }
                List<GallerySourceDescriptor> acceptedSources = new ArrayList<>();
                for (GallerySourceDescriptor source : sources) {
                    validateSource(source, providerId);
                    for (GalleryKind kind : source.kinds()) {
                        SourceKindKey key = new SourceKindKey(source.sourceId(), kind);
                        String previous = sourceKindOwners.putIfAbsent(key, providerId);
                        if (previous != null) {
                            throw new IllegalStateException("duplicate gallery source/kind: "
                                    + source.sourceId() + "/" + kind + " (" + previous + " vs "
                                    + providerId + ")");
                        }
                    }
                    acceptedSources.add(source);
                    allSources.add(source);
                }
                byProviderId.put(providerId,
                        new RegisteredProvider(providerId, provider, acceptedSources));
            }
        }

        return new Snapshot(byProviderId.values().stream().toList(), allSources, diagnostics);
    }

    private static List<GalleryDataProvider> immutableProviderList(List<GalleryDataProvider> providers) {
        return Collections.unmodifiableList(new ArrayList<>(providers));
    }

    private static String normalizeOwner(String ownerPluginId) {
        String owner = blankToNull(ownerPluginId);
        if (owner == null) {
            throw new IllegalArgumentException("gallery provider owner plugin id must not be blank");
        }
        return owner;
    }

    private static ProviderIdLookup providerId(GalleryDataProvider provider, List<GalleryDiagnostic> diagnostics) {
        try {
            return new ProviderIdLookup(blankToNull(provider.providerId()), true);
        } catch (RuntimeException e) {
            diagnostics.add(diagnostic(null, "provider-id-failed", e));
            return new ProviderIdLookup(null, false);
        }
    }

    private static List<GallerySourceDescriptor> sources(GalleryDataProvider provider,
                                                         String providerId,
                                                         List<GalleryDiagnostic> diagnostics) {
        try {
            List<GallerySourceDescriptor> sources = provider.sources();
            if (sources == null) {
                diagnostics.add(new GalleryDiagnostic(
                        providerId, null, null, "provider-sources-null",
                        "Gallery provider returned null sources"));
                return null;
            }
            return sources;
        } catch (RuntimeException e) {
            diagnostics.add(diagnostic(providerId, "provider-sources-failed", e));
            return null;
        }
    }

    private static void validateSource(GallerySourceDescriptor source, String providerId) {
        if (source == null) {
            throw new IllegalStateException("null gallery source descriptor (provider: " + providerId + ")");
        }
        if (!providerId.equals(source.providerId())) {
            throw new IllegalStateException("gallery source providerId mismatch: declared "
                    + source.providerId() + " under provider " + providerId);
        }
        validateId(source.sourceId(), "gallery source id");
        if (source.kinds().isEmpty()) {
            throw new IllegalStateException("gallery source without kinds: "
                    + source.sourceId() + " (provider: " + providerId + ")");
        }
        for (GalleryKind kind : source.kinds()) {
            if (kind == null) {
                throw new IllegalStateException("gallery source has null kind: "
                        + source.sourceId() + " (provider: " + providerId + ")");
            }
        }
    }

    private static void validateId(String value, String label) {
        if (value == null || !ID_PATTERN.matcher(value).matches()) {
            throw new IllegalStateException("invalid " + label + ": " + value);
        }
    }

    private static boolean matches(GallerySourceDescriptor source, GalleryKind kind, String sourceId) {
        if (sourceId != null && !sourceId.equals(source.sourceId())) {
            return false;
        }
        return kind == null || source.kinds().contains(kind);
    }

    private static GalleryDiagnostic diagnostic(String providerId, String code, RuntimeException e) {
        String message = e.getClass().getSimpleName();
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            message += ": " + e.getMessage();
        }
        return new GalleryDiagnostic(providerId, null, null, code, message);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record SourceKindKey(String sourceId, GalleryKind kind) {
    }

    private record ProviderIdLookup(String providerId, boolean available) {
    }
}
