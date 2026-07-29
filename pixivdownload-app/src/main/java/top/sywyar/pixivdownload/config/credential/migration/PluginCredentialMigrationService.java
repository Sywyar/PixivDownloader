package top.sywyar.pixivdownload.config.credential.migration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.gui.config.PropertiesConfigFileEditor;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One-time, idempotent migration of legacy plugin credentials into the encrypted owner store.
 *
 * <p>Each successful step is monotonic: the encrypted value is written and reread before legacy
 * plugin properties are removed, and YAML is removed last. A later failure therefore leaves either
 * the original source or the verified encrypted target available for the next retry.
 */
@Slf4j
@Service
public class PluginCredentialMigrationService {

    private final PluginCredentialDefinitionResolver definitionResolver;
    private final PluginCredentialStore credentialStore;
    private final PluginCredentialEnvironmentMask environmentMask;
    private final LegacyCredentialSources legacySources;

    @Autowired
    public PluginCredentialMigrationService(PluginCredentialDefinitionResolver definitionResolver,
                                            PluginCredentialStore credentialStore,
                                            PluginCredentialEnvironmentMask environmentMask) {
        this(definitionResolver, credentialStore, environmentMask, new RuntimeFileLegacyCredentialSources());
    }

    PluginCredentialMigrationService(PluginCredentialDefinitionResolver definitionResolver,
                                     PluginCredentialStore credentialStore,
                                     PluginCredentialEnvironmentMask environmentMask,
                                     LegacyCredentialSources legacySources) {
        this.definitionResolver = Objects.requireNonNull(definitionResolver, "definitionResolver");
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
        this.environmentMask = Objects.requireNonNull(environmentMask, "environmentMask");
        this.legacySources = Objects.requireNonNull(legacySources, "legacySources");
    }

    /**
     * Migrates every valid installed owner. A broken owner is isolated and does not block the
     * remaining owners.
     */
    public void migrateAll() {
        PluginCredentialDefinitionResolver.Resolution resolution = definitionResolver.resolveSnapshot();
        try {
            resolution.failures().forEach((owner, failure) ->
                    log.warn(message(
                            "plugin.credential.migration.definition-rejected", owner, failure)));
            for (Map.Entry<String, Set<String>> entry : resolution.validDefinitions().entrySet()) {
                try {
                    migrateResolvedOwner(entry.getKey(), entry.getValue());
                } catch (IOException | RuntimeException e) {
                    log.warn(message(
                            "plugin.credential.migration.failed",
                            entry.getKey(),
                            safeMessage(e)), e);
                }
            }
        } finally {
            environmentMask.replace(resolution.maskKeys(), resolution.hostConfigKeys());
        }
    }

    /**
     * Migrates one currently installed owner before its child context is refreshed.
     *
     * @throws IOException when the encrypted target or a legacy source cannot be safely read,
     *                     written, verified, or cleaned
     */
    public void migrateOwner(String ownerPluginId) throws IOException {
        PluginCredentialDefinitionResolver.Resolution resolution = definitionResolver.resolveSnapshot();
        String owner = ownerPluginId == null ? "" : ownerPluginId.trim();
        try {
            if (owner.isEmpty()) {
                throw new IllegalArgumentException("ownerPluginId must not be blank");
            }
            String failure = resolution.failures().get(owner);
            if (failure != null) {
                throw new IllegalStateException(
                        "Invalid plugin credential definition for owner " + owner + ": " + failure);
            }
            migrateResolvedOwner(
                    owner,
                    resolution.validDefinitions().getOrDefault(owner, Set.of()));
        } finally {
            environmentMask.replace(resolution.maskKeys(), resolution.hostConfigKeys());
        }
    }

    private void migrateResolvedOwner(String owner, Set<String> sensitiveKeys) throws IOException {
        if (sensitiveKeys == null || sensitiveKeys.isEmpty()) {
            return;
        }
        credentialStore.withOwnerLocks(
                Set.of(owner),
                () -> migrateResolvedOwnerWithOwnerLock(owner, sensitiveKeys));
    }

    private void migrateResolvedOwnerWithOwnerLock(
            String owner, Set<String> sensitiveKeys) throws IOException {
        // This is intentionally first. A corrupt/unknown/authentication-failed target must never be
        // replaced by an older properties/YAML value.
        Map<String, String> stored = credentialStore.readAll(owner);
        Map<String, String> pluginProperties = legacySources.readPluginProperties(owner, sensitiveKeys);
        Map<String, String> yaml = legacySources.readYaml(sensitiveKeys);

        Map<String, String> effective = new LinkedHashMap<>();
        Map<String, String> updates = new LinkedHashMap<>();
        for (String key : sensitiveKeys) {
            String value = firstNonBlank(
                    stored.get(key),
                    pluginProperties.get(key),
                    yaml.get(key));
            boolean present = stored.containsKey(key)
                    || pluginProperties.containsKey(key)
                    || yaml.containsKey(key);
            if (!present) {
                continue;
            }
            if (value == null) {
                value = "";
            }
            effective.put(key, value);
            if (!stored.containsKey(key) && !value.isBlank()) {
                updates.put(key, value);
            }
        }

        if (!updates.isEmpty()) {
            credentialStore.update(owner, updates);
        }
        Map<String, String> verified = credentialStore.readAll(owner);
        verifyEffectiveValues(owner, effective, verified);

        Set<String> propertyKeysToRemove = intersection(sensitiveKeys, pluginProperties.keySet());
        if (!propertyKeysToRemove.isEmpty()) {
            legacySources.removePluginProperties(owner, propertyKeysToRemove);
            Map<String, String> remaining =
                    legacySources.readPluginProperties(owner, propertyKeysToRemove);
            if (!remaining.isEmpty()) {
                throw new IOException(
                        "Legacy plugin credential removal verification failed for owner: " + owner);
            }
        }

        Set<String> yamlKeysToRemove = intersection(sensitiveKeys, yaml.keySet());
        if (!yamlKeysToRemove.isEmpty()) {
            legacySources.removeYaml(yamlKeysToRemove);
            Map<String, String> remaining = legacySources.readYaml(yamlKeysToRemove);
            if (!remaining.isEmpty()) {
                throw new IOException(
                        "Legacy YAML credential removal verification failed for owner: " + owner);
            }
        }
    }

    private static void verifyEffectiveValues(String owner,
                                              Map<String, String> effective,
                                              Map<String, String> verified) throws IOException {
        for (Map.Entry<String, String> entry : effective.entrySet()) {
            String expected = entry.getValue();
            String actual = verified.get(entry.getKey());
            if (expected == null || expected.isBlank()) {
                if (actual != null && !actual.isBlank()) {
                    throw new IOException(
                            "Plugin credential clear verification failed for owner: " + owner);
                }
                continue;
            }
            if (!Objects.equals(expected, actual)) {
                throw new IOException(
                        "Plugin credential migration verification failed for owner: " + owner);
            }
        }
    }

    private static Set<String> intersection(Set<String> declared, Set<String> present) {
        LinkedHashSet<String> result = new LinkedHashSet<>(declared);
        result.retainAll(present);
        return Set.copyOf(result);
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static String safeMessage(Throwable failure) {
        String detail = failure == null ? null : failure.getMessage();
        return detail == null || detail.isBlank()
                ? MessageBundles.get("gui.log.no-detail")
                : detail;
    }

    private static String message(String code, Object... args) {
        return MessageBundles.get(code, args);
    }

    interface LegacyCredentialSources {

        Map<String, String> readPluginProperties(String owner, Set<String> keys) throws IOException;

        Map<String, String> readYaml(Set<String> keys) throws IOException;

        void removePluginProperties(String owner, Set<String> keys) throws IOException;

        void removeYaml(Set<String> keys) throws IOException;
    }

    private static final class RuntimeFileLegacyCredentialSources implements LegacyCredentialSources {

        @Override
        public Map<String, String> readPluginProperties(String owner, Set<String> keys)
                throws IOException {
            return pluginEditor(owner).readAll(keys);
        }

        @Override
        public Map<String, String> readYaml(Set<String> keys) throws IOException {
            return yamlEditor().readAll(keys);
        }

        @Override
        public void removePluginProperties(String owner, Set<String> keys) throws IOException {
            pluginEditor(owner).removeAll(keys);
        }

        @Override
        public void removeYaml(Set<String> keys) throws IOException {
            yamlEditor().removeAll(keys);
        }

        private static PropertiesConfigFileEditor pluginEditor(String owner) {
            return new PropertiesConfigFileEditor(
                    RuntimeFiles.resolvePluginConfigPath(owner, "properties"));
        }

        private static ConfigFileEditor yamlEditor() {
            return new ConfigFileEditor(RuntimeFiles.resolveConfigYamlPath());
        }
    }
}
