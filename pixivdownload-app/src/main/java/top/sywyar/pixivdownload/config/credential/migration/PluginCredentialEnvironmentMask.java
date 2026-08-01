package top.sywyar.pixivdownload.config.credential.migration;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.PluginConfigPropertySourceLoader;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Masks credential keys across the host and plugin context boundary.
 *
 * <p>Plugin-declared and non-host legacy credentials are tombstoned in the parent environment.
 * Host-owned credential-like keys remain available to host beans, but join the broader mask
 * installed in every plugin child context. An owner child installs its scoped credential source
 * above that child mask, so it can read only its own declared values.
 */
@Component
public class PluginCredentialEnvironmentMask {

    public static final String PROPERTY_SOURCE_NAME = "pixivdownloadPluginCredentialMask";

    private final ConfigurableEnvironment environment;
    private final LinkedHashSet<String> parentTombstones = new LinkedHashSet<>();
    private final LinkedHashSet<String> childMaskKeys = new LinkedHashSet<>();

    public PluginCredentialEnvironmentMask(ConfigurableEnvironment environment) {
        this.environment = java.util.Objects.requireNonNull(environment, "environment");
    }

    synchronized void replace(Set<String> sensitiveKeys, Set<String> hostConfigKeys) {
        if (sensitiveKeys != null) {
            parentTombstones.addAll(sensitiveKeys);
            childMaskKeys.addAll(sensitiveKeys);
        }
        Set<String> hostKeys = hostConfigKeys == null ? Set.of() : hostConfigKeys;
        for (String hostKey : hostKeys) {
            if (PluginConfigPropertySourceLoader.isCredentialLikeKey(hostKey)) {
                childMaskKeys.add(hostKey);
            }
        }
        Set<String> discovered = discoverLegacyCredentialKeys();
        childMaskKeys.addAll(discovered);
        for (String key : discovered) {
            if (!hostKeys.contains(key)) {
                parentTombstones.add(key);
            }
        }
        MutablePropertySources sources = environment.getPropertySources();
        if (parentTombstones.isEmpty()) {
            sources.remove(PROPERTY_SOURCE_NAME);
            return;
        }
        LinkedHashMap<String, Object> masked = new LinkedHashMap<>();
        for (String key : new TreeSet<>(parentTombstones)) {
            masked.put(key, "");
        }
        MapPropertySource next = new MapPropertySource(PROPERTY_SOURCE_NAME, masked);
        if (sources.contains(PROPERTY_SOURCE_NAME)) {
            sources.replace(PROPERTY_SOURCE_NAME, next);
        } else {
            sources.addFirst(next);
        }
    }

    public synchronized Set<String> maskKeys() {
        return Set.copyOf(childMaskKeys);
    }

    private Set<String> discoverLegacyCredentialKeys() {
        LinkedHashSet<String> discovered = new LinkedHashSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (PROPERTY_SOURCE_NAME.equals(source.getName())
                    || !(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String propertyName : enumerable.getPropertyNames()) {
                if (propertyName == null) {
                    continue;
                }
                if (PluginConfigPropertySourceLoader.isCredentialLikeKey(propertyName)) {
                    discovered.add(propertyName);
                }
            }
        }
        return Set.copyOf(discovered);
    }
}
