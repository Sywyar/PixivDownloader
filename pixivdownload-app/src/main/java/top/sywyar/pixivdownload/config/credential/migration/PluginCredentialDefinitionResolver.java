package top.sywyar.pixivdownload.config.credential.migration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.DefaultConfigTemplate;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Resolves owner-scoped credential definitions from installed plugin contributions.
 *
 * <p>The owner always comes from the registry identity captured at registration time. Plugin
 * instances cannot redirect credentials by changing or repeatedly reporting their own id.
 */
@Component
public class PluginCredentialDefinitionResolver {

    private final PluginRegistry pluginRegistry;
    private final Supplier<Set<String>> coreConfigKeys;

    @Autowired
    public PluginCredentialDefinitionResolver(PluginRegistry pluginRegistry) {
        this(pluginRegistry, PluginCredentialDefinitionResolver::currentCoreConfigKeys);
    }

    PluginCredentialDefinitionResolver(PluginRegistry pluginRegistry,
                                       Supplier<Set<String>> coreConfigKeys) {
        this.pluginRegistry = java.util.Objects.requireNonNull(pluginRegistry, "pluginRegistry");
        this.coreConfigKeys = java.util.Objects.requireNonNull(coreConfigKeys, "coreConfigKeys");
    }

    /**
     * Returns valid installed owner definitions, including plugins disabled in the active snapshot.
     * Owners with malformed, duplicate, or host-owned declarations are excluded.
     */
    public Map<String, Set<String>> resolveAll() {
        return resolveSnapshot().validDefinitions();
    }

    /**
     * Resolves one installed owner. Invalid declarations fail closed instead of exposing a partial
     * credential view.
     */
    public Set<String> resolveForOwner(String ownerPluginId) {
        String owner = requireOwner(ownerPluginId);
        Resolution resolution = resolveSnapshot();
        String failure = resolution.failures().get(owner);
        if (failure != null) {
            throw new IllegalStateException(
                    "Invalid plugin credential definition for owner " + owner + ": " + failure);
        }
        return resolution.validDefinitions().getOrDefault(owner, Set.of());
    }

    Resolution resolveSnapshot() {
        Set<String> hostKeys = Set.copyOf(coreConfigKeys.get());
        Map<String, LinkedHashSet<String>> discovered = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> owned = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();

        for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.allRegisteredPlugins()) {
            if (registered == null) {
                continue;
            }
            String owner = requireOwner(registered.id());
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            discovered.put(owner, keys);
            owned.put(owner, new LinkedHashSet<>());
            try {
                collectSensitiveKeys(registered, keys);
            } catch (RuntimeException | IOException e) {
                failures.put(owner, safeMessage(e));
            }
        }

        Map<String, List<String>> ownersByKey = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : discovered.entrySet()) {
            for (String key : entry.getValue()) {
                if (hostKeys.contains(key)) {
                    failures.putIfAbsent(entry.getKey(), "credential key is owned by the host: " + key);
                    continue;
                }
                if (!isOwnedNamespace(entry.getKey(), key)) {
                    failures.putIfAbsent(
                            entry.getKey(),
                            "credential key namespace does not belong to owner: " + key);
                    continue;
                }
                owned.get(entry.getKey()).add(key);
                ownersByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry.getKey());
            }
        }
        for (Map.Entry<String, List<String>> entry : ownersByKey.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            String reason = "credential key is declared by multiple owners: " + entry.getKey();
            for (String owner : entry.getValue()) {
                failures.putIfAbsent(owner, reason);
            }
        }

        Map<String, Set<String>> valid = new LinkedHashMap<>();
        LinkedHashSet<String> maskKeys = new LinkedHashSet<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : discovered.entrySet()) {
            LinkedHashSet<String> safeOwnerKeys = new LinkedHashSet<>(entry.getValue());
            safeOwnerKeys.removeAll(hostKeys);
            maskKeys.addAll(safeOwnerKeys);
            if (!failures.containsKey(entry.getKey())) {
                valid.put(entry.getKey(), Set.copyOf(owned.get(entry.getKey())));
            }
        }
        return new Resolution(
                immutableDefinitions(valid),
                Set.copyOf(maskKeys),
                hostKeys,
                Map.copyOf(failures));
    }

    private static void collectSensitiveKeys(PluginRegistry.RegisteredPlugin registered,
                                             Set<String> keys) throws IOException {
        List<GuiConfigContribution> contributions = registered.plugin().guiConfigContributions();
        if (contributions == null) {
            throw new IllegalStateException("GUI config contribution list is null");
        }
        for (GuiConfigContribution contribution : contributions) {
            if (contribution == null) {
                throw new IllegalStateException("GUI config contribution is null");
            }
            Collection<GuiConfigFieldContribution> fields = contribution.fields();
            if (fields == null) {
                throw new IllegalStateException("GUI config field list is null");
            }
            for (GuiConfigFieldContribution field : fields) {
                if (field == null) {
                    throw new IllegalStateException("GUI config field is null");
                }
                if (!field.sensitive() && field.type() != GuiConfigFieldType.PASSWORD) {
                    continue;
                }
                String key = ConfigFileEditor.requireSafeKey(field.key());
                if (!keys.add(key)) {
                    throw new IllegalStateException("duplicate credential key: " + key);
                }
            }
        }
    }

    private static Set<String> currentCoreConfigKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        DefaultConfigTemplate.build(code -> code).lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#") && line.contains(":"))
                .map(line -> line.substring(0, line.indexOf(':')).trim())
                .filter(key -> !key.isEmpty())
                .forEach(keys::add);
        return Set.copyOf(keys);
    }

    private static boolean isOwnedNamespace(String owner, String key) {
        int separator = key.indexOf('.');
        String namespace = (separator < 0 ? key : key.substring(0, separator))
                .toLowerCase(java.util.Locale.ROOT);
        String normalizedOwner = owner.toLowerCase(java.util.Locale.ROOT);
        return namespace.equals(normalizedOwner)
                || namespace.endsWith("-" + normalizedOwner);
    }

    private static Map<String, Set<String>> immutableDefinitions(Map<String, Set<String>> source) {
        LinkedHashMap<String, Set<String>> result = new LinkedHashMap<>();
        source.forEach((owner, keys) -> result.put(owner, Set.copyOf(keys)));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static String requireOwner(String ownerPluginId) {
        String owner = ownerPluginId == null ? "" : ownerPluginId.trim();
        if (owner.isEmpty()) {
            throw new IllegalArgumentException("ownerPluginId must not be blank");
        }
        return owner;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    record Resolution(Map<String, Set<String>> validDefinitions,
                      Set<String> maskKeys,
                      Set<String> hostConfigKeys,
                      Map<String, String> failures) {

        Resolution {
            validDefinitions = immutableDefinitions(validDefinitions);
            maskKeys = Set.copyOf(maskKeys);
            hostConfigKeys = Set.copyOf(hostConfigKeys);
            failures = Map.copyOf(failures);
        }
    }
}
