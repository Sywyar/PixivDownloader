package top.sywyar.pixivdownload.core.ai;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.ai.AiChatClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AiChatClientRegistry {

    private final Object lock = new Object();
    private final Map<String, AiChatClient> byPlugin = new LinkedHashMap<>();
    private volatile AiChatClient active;

    public AiChatClientRegistry(List<AiChatClient> initialClients) {
        if (initialClients != null && !initialClients.isEmpty()) {
            register("core", initialClients);
        }
    }

    public void register(String pluginId, List<AiChatClient> clients) {
        synchronized (lock) {
            if (clients == null || clients.isEmpty()) {
                byPlugin.remove(pluginId);
            } else if (clients.size() == 1) {
                byPlugin.put(pluginId, clients.get(0));
            } else {
                throw new IllegalStateException("plugin '" + pluginId + "' contributed multiple AI chat clients");
            }
            active = byPlugin.values().stream().findFirst().orElse(null);
        }
    }

    public void unregister(String pluginId) {
        synchronized (lock) {
            byPlugin.remove(pluginId);
            active = byPlugin.values().stream().findFirst().orElse(null);
        }
    }

    public Optional<AiChatClient> active() {
        return Optional.ofNullable(active);
    }
}
