package top.sywyar.pixivdownload.core.notification;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.notification.NotificationSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class NotificationSinkRegistry {

    private final Object lock = new Object();
    private final Map<String, List<NotificationSink>> byPlugin = new LinkedHashMap<>();
    private volatile List<NotificationSink> snapshot = List.of();

    public NotificationSinkRegistry(List<NotificationSink> initialSinks) {
        if (initialSinks != null && !initialSinks.isEmpty()) {
            byPlugin.put("core", List.copyOf(initialSinks));
            rebuild();
        }
    }

    public void register(String pluginId, List<NotificationSink> sinks) {
        synchronized (lock) {
            if (sinks == null || sinks.isEmpty()) {
                byPlugin.remove(pluginId);
            } else {
                byPlugin.put(pluginId, List.copyOf(sinks));
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

    public List<NotificationSink> sinks() {
        return snapshot;
    }

    private void rebuild() {
        List<NotificationSink> next = new ArrayList<>();
        for (List<NotificationSink> sinks : byPlugin.values()) {
            next.addAll(sinks);
        }
        snapshot = List.copyOf(next);
    }
}
