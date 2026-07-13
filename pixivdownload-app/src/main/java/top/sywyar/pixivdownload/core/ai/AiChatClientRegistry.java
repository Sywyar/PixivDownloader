package top.sywyar.pixivdownload.core.ai;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.ai.AiChatClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AiChatClientRegistry {

    private record OwnerKey(String pluginId, long publicationId) {
    }

    private final Object lock = new Object();
    private final Map<OwnerKey, AiChatClient> byOwner = new LinkedHashMap<>();
    private volatile AiChatClient active;

    public AiChatClientRegistry(List<AiChatClient> initialClients) {
        if (initialClients != null && !initialClients.isEmpty()) {
            register("core", initialClients);
        }
    }

    public void register(String pluginId, List<AiChatClient> clients) {
        registerPrepared(pluginId, 0L, clients);
    }

    /** Register proxies for one exact external capability publication without invoking them. */
    public void registerPrepared(String pluginId, long publicationId, List<AiChatClient> clients) {
        synchronized (lock) {
            OwnerKey owner = owner(pluginId, publicationId);
            if (clients == null || clients.isEmpty()) {
                byOwner.remove(owner);
            } else if (clients.size() == 1) {
                if (clients.get(0) == null) {
                    throw new IllegalStateException("plugin '" + pluginId + "' contributed a null AI chat client");
                }
                byOwner.put(owner, clients.get(0));
            } else {
                throw new IllegalStateException("plugin '" + pluginId + "' contributed multiple AI chat clients");
            }
            active = byOwner.values().stream().findFirst().orElse(null);
        }
    }

    public void unregister(String pluginId) {
        synchronized (lock) {
            byOwner.keySet().removeIf(owner -> owner.pluginId().equals(pluginId));
            active = byOwner.values().stream().findFirst().orElse(null);
        }
    }

    /** Exact withdrawal; an old publication cannot remove a same-plugin replacement. */
    public void unregisterPrepared(String pluginId, long publicationId) {
        synchronized (lock) {
            byOwner.remove(owner(pluginId, publicationId));
            active = byOwner.values().stream().findFirst().orElse(null);
        }
    }

    public Optional<AiChatClient> active() {
        return Optional.ofNullable(active);
    }

    private static OwnerKey owner(String pluginId, long publicationId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("AI capability plugin id must not be blank");
        }
        if (publicationId < 0L) {
            throw new IllegalArgumentException("AI capability publication id must not be negative");
        }
        return new OwnerKey(pluginId.trim(), publicationId);
    }
}
