package top.sywyar.pixivdownload.config.credential;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.credential.migration.PluginCredentialDefinitionResolver;
import top.sywyar.pixivdownload.plugin.api.credential.PluginCredentialException;
import top.sywyar.pixivdownload.plugin.api.credential.PluginCredentials;
import top.sywyar.pixivdownload.plugin.api.resource.PluginResourceException;
import top.sywyar.pixivdownload.plugin.api.resource.PluginResources;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginCredentialsFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static top.sywyar.pixivdownload.plugin.api.credential.PluginCredentialException.Reason.*;

/** 在既有资源 admission 与存储事务内执行凭据操作，不另建生命周期。 */
@Component
public final class OwnerPluginCredentials implements PluginCredentialsFactory {
    private final PluginCredentialStore store;
    private final PluginCredentialDefinitionResolver definitions;

    public OwnerPluginCredentials(PluginCredentialStore store, PluginCredentialDefinitionResolver definitions) {
        this.store = store;
        this.definitions = definitions;
    }

    @Override
    public PluginCredentials create(String pluginId, PluginResources resources) {
        Set<String> keys;
        try {
            keys = Set.copyOf(definitions.resolveForOwner(pluginId));
        } catch (RuntimeException failure) {
            throw new PluginCredentialException(INVALID_DEFINITION);
        }
        return new PluginCredentials() {
            @Override
            public Optional<String> read(String key) {
                return execute(resources, () -> {
                    requireKey(key);
                    return Optional.ofNullable(store.readAll(pluginId).get(key));
                });
            }

            @Override
            public void update(Map<String, String> updates) {
                execute(resources, () -> {
                    if (updates == null) throw new PluginCredentialException(INVALID_UPDATE);
                    if (updates.size() > 128) throw new PluginCredentialException(CAPACITY);
                    Map<String, String> checked = new LinkedHashMap<>();
                    int bytes = 0;
                    for (var entry : updates.entrySet()) {
                        requireKey(entry.getKey());
                        String value = entry.getValue();
                        if (value == null) throw new PluginCredentialException(INVALID_UPDATE);
                        if (value.length() > 64 * 1024) throw new PluginCredentialException(CAPACITY);
                        int size = value.getBytes(StandardCharsets.UTF_8).length;
                        bytes += size;
                        if (size > 64 * 1024 || bytes > 1024 * 1024) throw new PluginCredentialException(CAPACITY);
                        checked.put(entry.getKey(), value);
                    }
                    store.update(pluginId, checked);
                    return null;
                });
            }

            private void requireKey(String key) {
                if (key == null || !keys.contains(key)) throw new PluginCredentialException(UNDECLARED);
            }
        };
    }

    private static <T> T execute(PluginResources resources, IoOperation<T> operation) {
        try (var scope = resources.openScope()) {
            return scope.acquire(() -> {
                try {
                    return new Result<>(operation.run());
                } catch (IOException failure) {
                    throw new PluginCredentialException(STORAGE_FAILED);
                }
            }).value();
        } catch (PluginResourceException failure) {
            throw new PluginCredentialException(CLOSED);
        } catch (PluginCredentialException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new PluginCredentialException(STORAGE_FAILED);
        }
    }

    @FunctionalInterface
    private interface IoOperation<T> { T run() throws IOException; }

    private record Result<T>(T value) implements AutoCloseable {
        @Override public void close() { }
    }
}
