package top.sywyar.pixivdownload.config.credential;

import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.config.credential.migration.PluginCredentialDefinitionResolver;
import top.sywyar.pixivdownload.config.credential.migration.PluginCredentialEnvironmentMask;
import top.sywyar.pixivdownload.config.credential.migration.PluginCredentialMigrationService;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextPropertySnapshot;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Supplies one plugin child context with only its declared, decrypted credential properties.
 *
 * <p>The global sensitive-key set is used solely as a fall-through mask. Values from another
 * owner, undeclared stale entries, root keys, and filesystem paths never enter the snapshot.
 */
@Service
public class PluginCredentialPropertySourceService {

    private final PluginCredentialStore credentialStore;
    private final PluginCredentialDefinitionResolver definitionResolver;
    private final PluginCredentialMigrationService migrationService;
    private final PluginCredentialEnvironmentMask environmentMask;

    public PluginCredentialPropertySourceService(
            PluginCredentialStore credentialStore,
            PluginCredentialDefinitionResolver definitionResolver,
            PluginCredentialMigrationService migrationService,
            PluginCredentialEnvironmentMask environmentMask) {
        this.credentialStore = java.util.Objects.requireNonNull(
                credentialStore, "credentialStore");
        this.definitionResolver = java.util.Objects.requireNonNull(
                definitionResolver, "definitionResolver");
        this.migrationService = java.util.Objects.requireNonNull(
                migrationService, "migrationService");
        this.environmentMask = java.util.Objects.requireNonNull(
                environmentMask, "environmentMask");
    }

    public PluginContextPropertySnapshot snapshotFor(String ownerPluginId) {
        String owner = requireOwner(ownerPluginId);
        try {
            migrationService.migrateOwner(owner);
            Map<String, Set<String>> definitions = definitionResolver.resolveAll();
            Set<String> ownerKeys = definitions.getOrDefault(owner, Set.of());
            Set<String> globalSensitiveKeys = union(definitions, environmentMask.maskKeys());
            if (ownerKeys.isEmpty()) {
                return new PluginContextPropertySnapshot(Map.of(), globalSensitiveKeys);
            }

            Map<String, String> stored = credentialStore.readAll(owner);
            LinkedHashMap<String, Object> ownerProperties = new LinkedHashMap<>();
            for (String key : ownerKeys) {
                String value = stored.get(key);
                if (value != null && !value.isBlank()) {
                    ownerProperties.put(key, value);
                }
            }
            return new PluginContextPropertySnapshot(ownerProperties, globalSensitiveKeys);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to prepare plugin credentials for owner: " + owner, e);
        }
    }

    private static Set<String> union(Map<String, Set<String>> definitions,
                                     Set<String> environmentMaskKeys) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        definitions.values().forEach(keys::addAll);
        keys.addAll(environmentMaskKeys);
        return Set.copyOf(keys);
    }

    private static String requireOwner(String ownerPluginId) {
        String owner = ownerPluginId == null ? "" : ownerPluginId.trim();
        if (owner.isEmpty()) {
            throw new IllegalArgumentException("ownerPluginId must not be blank");
        }
        return owner;
    }
}
