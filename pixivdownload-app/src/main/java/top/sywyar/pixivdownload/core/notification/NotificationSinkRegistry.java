package top.sywyar.pixivdownload.core.notification;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.notification.NotificationSink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Host registry for notification sink proxies and captured diagnostic metadata. */
@Component
public class NotificationSinkRegistry {

    public record PreparedSink(String medium, NotificationSink sink, String implementationType) {
        public PreparedSink {
            if (medium == null || medium.isBlank()) {
                throw new IllegalArgumentException("notification sink medium must not be blank");
            }
            medium = medium.trim();
            if (sink == null) {
                throw new IllegalArgumentException("notification sink proxy must not be null");
            }
            implementationType = implementationType == null || implementationType.isBlank()
                    ? "unknown" : implementationType;
        }
    }

    private record OwnerKey(String pluginId, long publicationId) {
    }

    private record Snapshot(List<PreparedSink> preparedSinks, List<NotificationSink> sinks) {
        private Snapshot {
            preparedSinks = List.copyOf(preparedSinks);
            sinks = List.copyOf(sinks);
        }

        private static Snapshot empty() {
            return new Snapshot(List.of(), List.of());
        }
    }

    private record State(Map<OwnerKey, List<PreparedSink>> byOwner, Snapshot snapshot) {
        private State {
            byOwner = Collections.unmodifiableMap(new LinkedHashMap<>(byOwner));
            snapshot = java.util.Objects.requireNonNull(snapshot, "notification sink snapshot");
        }

        private static State empty() {
            return new State(Map.of(), Snapshot.empty());
        }
    }

    private final Object lock = new Object();
    private final Runnable statePublishProbe;
    private volatile State state = State.empty();

    @Autowired
    public NotificationSinkRegistry(List<NotificationSink> initialSinks) {
        this(initialSinks, () -> {
        });
    }

    NotificationSinkRegistry(List<NotificationSink> initialSinks, Runnable statePublishProbe) {
        this.statePublishProbe = java.util.Objects.requireNonNull(
                statePublishProbe, "notification state publish probe");
        if (initialSinks != null && !initialSinks.isEmpty()) {
            register("core", initialSinks);
        }
    }

    /** Legacy/core path. External adapters use registerPrepared. */
    public void register(String pluginId, List<NotificationSink> sinks) {
        List<PreparedSink> prepared = new ArrayList<>();
        if (sinks != null) {
            for (NotificationSink sink : sinks) {
                if (sink != null) {
                    prepared.add(new PreparedSink(
                            sink.medium(), sink, sink.getClass().getName()));
                }
            }
        }
        registerPrepared(pluginId, 0L, prepared);
    }

    /** Publish captured metadata plus proxies without invoking a proxy while rebuilding. */
    public void registerPrepared(String pluginId, long publicationId, List<PreparedSink> sinks) {
        OwnerKey owner = owner(pluginId, publicationId);
        List<PreparedSink> copy = sinks == null ? List.of() : List.copyOf(sinks);
        synchronized (lock) {
            State current = state;
            Map<OwnerKey, List<PreparedSink>> next = new LinkedHashMap<>(current.byOwner());
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
            Map<OwnerKey, List<PreparedSink>> next = new LinkedHashMap<>(current.byOwner());
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
            Map<OwnerKey, List<PreparedSink>> next = new LinkedHashMap<>(current.byOwner());
            next.remove(owner);
            publishState(new State(next, rebuild(next)));
        }
    }

    public List<NotificationSink> sinks() {
        return state.snapshot().sinks();
    }

    List<PreparedSink> preparedSinks() {
        return state.snapshot().preparedSinks();
    }

    private void publishState(State next) {
        statePublishProbe.run();
        state = next;
    }

    private static Snapshot rebuild(Map<OwnerKey, List<PreparedSink>> owners) {
        List<PreparedSink> prepared = new ArrayList<>();
        List<NotificationSink> next = new ArrayList<>();
        for (List<PreparedSink> sinks : owners.values()) {
            for (PreparedSink sink : sinks) {
                prepared.add(sink);
                next.add(sink.sink());
            }
        }
        return new Snapshot(prepared, next);
    }

    private static OwnerKey owner(String pluginId, long publicationId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("notification capability plugin id must not be blank");
        }
        if (publicationId < 0L) {
            throw new IllegalArgumentException("notification capability publication id must not be negative");
        }
        return new OwnerKey(pluginId.trim(), publicationId);
    }
}
