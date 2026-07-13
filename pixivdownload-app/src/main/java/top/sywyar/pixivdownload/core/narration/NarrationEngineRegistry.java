package top.sywyar.pixivdownload.core.narration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Host registry for narration engine proxies and their already captured stable ids. */
@Component
public class NarrationEngineRegistry {

    /** Metadata captured from a raw child target before proxy publication. */
    public record PreparedEngine(String id, NarrationVoiceEngine engine, String implementationType) {
        public PreparedEngine {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("narration engine id must not be blank");
            }
            id = id.trim().toLowerCase(Locale.ROOT);
            if (engine == null) {
                throw new IllegalArgumentException("narration engine proxy must not be null");
            }
            implementationType = implementationType == null || implementationType.isBlank()
                    ? "unknown" : implementationType;
        }
    }

    private record OwnerKey(String pluginId, long publicationId) {
    }

    private record Snapshot(
            Map<String, NarrationVoiceEngine> byId,
            Map<String, PreparedEngine> preparedById,
            List<NarrationVoiceEngine> all) {
        private Snapshot {
            byId = Map.copyOf(byId);
            preparedById = Map.copyOf(preparedById);
            all = List.copyOf(all);
        }

        private static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), List.of());
        }
    }

    private record State(Map<OwnerKey, List<PreparedEngine>> byOwner, Snapshot snapshot) {
        private State {
            byOwner = Collections.unmodifiableMap(new LinkedHashMap<>(byOwner));
            snapshot = java.util.Objects.requireNonNull(snapshot, "narration engine snapshot");
        }

        private static State empty() {
            return new State(Map.of(), Snapshot.empty());
        }
    }

    private final Object lock = new Object();
    private final Runnable statePublishProbe;
    private volatile State state = State.empty();

    @Autowired
    public NarrationEngineRegistry(List<NarrationVoiceEngine> engines) {
        this(engines, () -> {
        });
    }

    NarrationEngineRegistry(List<NarrationVoiceEngine> engines, Runnable statePublishProbe) {
        this.statePublishProbe = java.util.Objects.requireNonNull(
                statePublishProbe, "narration state publish probe");
        if (engines != null && !engines.isEmpty()) {
            register("core", engines);
        }
    }

    /** Legacy/core registration path; external adapters use {@link #registerPrepared}. */
    public void register(String pluginId, List<NarrationVoiceEngine> engines) {
        List<PreparedEngine> prepared = new ArrayList<>();
        if (engines != null) {
            for (NarrationVoiceEngine engine : engines) {
                if (engine == null) {
                    continue;
                }
                String rawId = engine.id();
                if (rawId == null || rawId.isBlank()) {
                    throw new IllegalStateException(
                            "narration engine has blank id: " + engine.getClass().getName());
                }
                prepared.add(new PreparedEngine(rawId, engine, engine.getClass().getName()));
            }
        }
        registerPrepared(pluginId, 0L, prepared);
    }

    /** Publish metadata plus target-free proxies without calling any proxy during snapshot rebuild. */
    public void registerPrepared(String pluginId, long publicationId, List<PreparedEngine> engines) {
        OwnerKey owner = owner(pluginId, publicationId);
        List<PreparedEngine> copy = engines == null ? List.of() : List.copyOf(engines);
        synchronized (lock) {
            State current = state;
            Map<OwnerKey, List<PreparedEngine>> next = new LinkedHashMap<>(current.byOwner());
            if (copy.isEmpty()) {
                next.remove(owner);
            } else {
                next.put(owner, copy);
            }
            publishState(new State(next, rebuild(next)));
        }
    }

    public void unregister(String pluginId) {
        synchronized (lock) {
            State current = state;
            Map<OwnerKey, List<PreparedEngine>> next = new LinkedHashMap<>(current.byOwner());
            next.keySet().removeIf(owner -> owner.pluginId().equals(pluginId));
            publishState(new State(next, rebuild(next)));
        }
    }

    /** Exact withdrawal; a stale publication cannot delete a replacement. */
    public void unregisterPrepared(String pluginId, long publicationId) {
        synchronized (lock) {
            OwnerKey owner = owner(pluginId, publicationId);
            State current = state;
            if (!current.byOwner().containsKey(owner)) {
                return;
            }
            Map<OwnerKey, List<PreparedEngine>> next = new LinkedHashMap<>(current.byOwner());
            next.remove(owner);
            publishState(new State(next, rebuild(next)));
        }
    }

    /** Case-insensitive id lookup. */
    public Optional<NarrationVoiceEngine> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(state.snapshot().byId().get(id.trim().toLowerCase(Locale.ROOT)));
    }

    public Optional<NarrationVoiceEngine> selected(String engineId) {
        return byId(engineId);
    }

    public Optional<PreparedEngine> selectedPrepared(String engineId) {
        if (engineId == null || engineId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(state.snapshot().preparedById().get(
                engineId.trim().toLowerCase(Locale.ROOT)));
    }

    public List<NarrationVoiceEngine> all() {
        return state.snapshot().all();
    }

    public int count() {
        return state.snapshot().all().size();
    }

    private void publishState(State next) {
        statePublishProbe.run();
        state = next;
    }

    private static Snapshot rebuild(Map<OwnerKey, List<PreparedEngine>> owners) {
        Map<String, PreparedEngine> metadataById = new LinkedHashMap<>();
        Map<String, NarrationVoiceEngine> enginesById = new LinkedHashMap<>();
        List<NarrationVoiceEngine> engines = new ArrayList<>();
        for (List<PreparedEngine> list : owners.values()) {
            for (PreparedEngine prepared : list) {
                PreparedEngine previous = metadataById.putIfAbsent(prepared.id(), prepared);
                if (previous != null) {
                    throw new IllegalStateException("duplicate narration engine id '" + prepared.id() + "': "
                            + previous.implementationType() + " vs " + prepared.implementationType());
                }
                enginesById.put(prepared.id(), prepared.engine());
                engines.add(prepared.engine());
            }
        }
        return new Snapshot(enginesById, metadataById, engines);
    }

    private static OwnerKey owner(String pluginId, long publicationId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("narration capability plugin id must not be blank");
        }
        if (publicationId < 0L) {
            throw new IllegalArgumentException("narration capability publication id must not be negative");
        }
        return new OwnerKey(pluginId.trim(), publicationId);
    }
}
