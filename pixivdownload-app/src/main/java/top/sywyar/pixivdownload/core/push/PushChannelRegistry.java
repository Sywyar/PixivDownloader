package top.sywyar.pixivdownload.core.push;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class PushChannelRegistry {

    private final Object lock = new Object();
    private final Map<String, List<PushChannel>> byPlugin = new LinkedHashMap<>();
    private volatile List<PushChannel> channels = List.of();
    private volatile Map<PushChannelType, PushChannel> byType = Map.of();

    public PushChannelRegistry(List<PushChannel> initialChannels) {
        if (initialChannels != null && !initialChannels.isEmpty()) {
            byPlugin.put("core", List.copyOf(initialChannels));
            rebuild();
        }
    }

    public void register(String pluginId, List<PushChannel> items) {
        synchronized (lock) {
            if (items == null || items.isEmpty()) {
                byPlugin.remove(pluginId);
            } else {
                byPlugin.put(pluginId, List.copyOf(items));
            }
            rebuild();
        }
    }

    public void unregister(String pluginId) {
        synchronized (lock) {
            byPlugin.remove(pluginId);
            rebuild();
        }
    }

    public List<PushChannel> channels() {
        return channels;
    }

    public Optional<PushChannel> byType(PushChannelType type) {
        return Optional.ofNullable(byType.get(type));
    }

    private void rebuild() {
        List<PushChannel> next = new ArrayList<>();
        Map<PushChannelType, PushChannel> nextByType = new EnumMap<>(PushChannelType.class);
        for (List<PushChannel> list : byPlugin.values()) {
            for (PushChannel channel : list) {
                if (channel == null || channel.type() == null) {
                    continue;
                }
                next.add(channel);
                PushChannel previous = nextByType.putIfAbsent(channel.type(), channel);
                if (previous != null) {
                    throw new IllegalStateException("duplicate push channel type '" + channel.type().id()
                            + "': " + previous.getClass().getName() + " vs " + channel.getClass().getName());
                }
            }
        }
        channels = List.copyOf(next);
        byType = Map.copyOf(nextByType);
    }
}
