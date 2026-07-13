package top.sywyar.pixivdownload.core.push;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Host registry for push channel proxies and their captured channel types. */
@Component
public class PushChannelRegistry {

    /** Metadata captured before the raw channel is hidden behind its parent-loader proxy. */
    public record PreparedChannel(
            PushChannelType type,
            PushChannel channel,
            String implementationType
    ) {
        public PreparedChannel {
            if (type == null) {
                throw new IllegalArgumentException("push channel type must not be null");
            }
            if (channel == null) {
                throw new IllegalArgumentException("push channel proxy must not be null");
            }
            implementationType = implementationType == null || implementationType.isBlank()
                    ? "unknown" : implementationType;
        }
    }

    private record OwnerKey(String pluginId, long publicationId) {
    }

    private record Snapshot(
            List<PreparedChannel> preparedChannels,
            List<PushChannel> channels,
            Map<PushChannelType, PushChannel> byType,
            Map<PushChannelType, PreparedChannel> preparedByType) {
        private Snapshot {
            preparedChannels = List.copyOf(preparedChannels);
            channels = List.copyOf(channels);
            byType = Map.copyOf(byType);
            preparedByType = Map.copyOf(preparedByType);
        }

        private static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), Map.of(), Map.of());
        }
    }

    private record State(Map<OwnerKey, List<PreparedChannel>> byOwner, Snapshot snapshot) {
        private State {
            byOwner = Collections.unmodifiableMap(new LinkedHashMap<>(byOwner));
            snapshot = java.util.Objects.requireNonNull(snapshot, "push channel snapshot");
        }

        private static State empty() {
            return new State(Map.of(), Snapshot.empty());
        }
    }

    private final Object lock = new Object();
    private final Runnable statePublishProbe;
    private volatile State state = State.empty();

    @Autowired
    public PushChannelRegistry(List<PushChannel> initialChannels) {
        this(initialChannels, () -> {
        });
    }

    PushChannelRegistry(List<PushChannel> initialChannels, Runnable statePublishProbe) {
        this.statePublishProbe = java.util.Objects.requireNonNull(
                statePublishProbe, "push state publish probe");
        if (initialChannels != null && !initialChannels.isEmpty()) {
            register("core", initialChannels);
        }
    }

    /** Legacy/core path. External adapters publish already captured metadata through registerPrepared. */
    public void register(String pluginId, List<PushChannel> items) {
        List<PreparedChannel> prepared = new ArrayList<>();
        if (items != null) {
            for (PushChannel channel : items) {
                if (channel == null) {
                    continue;
                }
                PushChannelType type = channel.type();
                if (type != null) {
                    prepared.add(new PreparedChannel(type, channel, channel.getClass().getName()));
                }
            }
        }
        registerPrepared(pluginId, 0L, prepared);
    }

    /** Publish metadata plus proxies without invoking any proxy during conflict checking or rebuild. */
    public void registerPrepared(String pluginId, long publicationId, List<PreparedChannel> items) {
        OwnerKey owner = owner(pluginId, publicationId);
        List<PreparedChannel> copy = items == null ? List.of() : List.copyOf(items);
        synchronized (lock) {
            State current = state;
            Map<OwnerKey, List<PreparedChannel>> next = new LinkedHashMap<>(current.byOwner());
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
            Map<OwnerKey, List<PreparedChannel>> next = new LinkedHashMap<>(current.byOwner());
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
            Map<OwnerKey, List<PreparedChannel>> next = new LinkedHashMap<>(current.byOwner());
            next.remove(owner);
            publishState(new State(next, rebuild(next)));
        }
    }

    public List<PushChannel> channels() {
        return state.snapshot().channels();
    }

    public Optional<PushChannel> byType(PushChannelType type) {
        return Optional.ofNullable(state.snapshot().byType().get(type));
    }

    List<PreparedChannel> preparedChannels() {
        return state.snapshot().preparedChannels();
    }

    Optional<PreparedChannel> preparedByType(PushChannelType type) {
        return Optional.ofNullable(state.snapshot().preparedByType().get(type));
    }

    private void publishState(State next) {
        statePublishProbe.run();
        state = next;
    }

    private static Snapshot rebuild(Map<OwnerKey, List<PreparedChannel>> owners) {
        List<PreparedChannel> preparedChannels = new ArrayList<>();
        List<PushChannel> channels = new ArrayList<>();
        Map<PushChannelType, PushChannel> byType = new EnumMap<>(PushChannelType.class);
        Map<PushChannelType, PreparedChannel> metadataByType = new EnumMap<>(PushChannelType.class);
        for (List<PreparedChannel> list : owners.values()) {
            for (PreparedChannel prepared : list) {
                PreparedChannel previous = metadataByType.putIfAbsent(prepared.type(), prepared);
                if (previous != null) {
                    throw new IllegalStateException("duplicate push channel type '" + prepared.type().id()
                            + "': " + previous.implementationType() + " vs " + prepared.implementationType());
                }
                preparedChannels.add(prepared);
                channels.add(prepared.channel());
                byType.put(prepared.type(), prepared.channel());
            }
        }
        return new Snapshot(preparedChannels, channels, byType, metadataByType);
    }

    private static OwnerKey owner(String pluginId, long publicationId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("push capability plugin id must not be blank");
        }
        if (publicationId < 0L) {
            throw new IllegalArgumentException("push capability publication id must not be negative");
        }
        return new OwnerKey(pluginId.trim(), publicationId);
    }
}
