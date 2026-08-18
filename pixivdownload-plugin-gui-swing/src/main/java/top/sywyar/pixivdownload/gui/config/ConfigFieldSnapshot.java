package top.sywyar.pixivdownload.gui.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable field, group and rich section snapshot consumed by {@code ConfigPanel}.
 */
public final class ConfigFieldSnapshot {

    private final List<ConfigGroupSpec> groupSpecs;
    private final List<ConfigFieldSpec> fields;
    private final Map<String, ConfigFieldSpec> fieldsByKey;
    private final List<GuiConfigSectionSpec> sections;
    private final List<GuiConfigContributionDiagnostic> diagnostics;

    public ConfigFieldSnapshot(List<String> groups,
                               List<ConfigFieldSpec> fields,
                               List<GuiConfigSectionSpec> sections,
                               List<GuiConfigContributionDiagnostic> diagnostics) {
        this(toGroupSpecs(groups), fields, sections, diagnostics, true);
    }

    public ConfigFieldSnapshot(List<String> groups,
                               List<ConfigFieldSpec> fields,
                               List<GuiConfigContributionDiagnostic> diagnostics) {
        this(groups, fields, List.of(), diagnostics);
    }

    private ConfigFieldSnapshot(List<ConfigGroupSpec> groupSpecs,
                                List<ConfigFieldSpec> fields,
                                List<GuiConfigSectionSpec> sections,
                                List<GuiConfigContributionDiagnostic> diagnostics,
                                boolean ignored) {
        this.groupSpecs = groupSpecs == null ? List.of() : List.copyOf(groupSpecs);
        this.fields = fields == null ? List.of() : List.copyOf(fields);
        this.fieldsByKey = indexFields(this.fields);
        this.sections = sections == null ? List.of() : List.copyOf(sections);
        this.diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static ConfigFieldSnapshot withGroupSpecs(List<ConfigGroupSpec> groupSpecs,
                                                     List<ConfigFieldSpec> fields,
                                                     List<GuiConfigSectionSpec> sections,
                                                     List<GuiConfigContributionDiagnostic> diagnostics) {
        return new ConfigFieldSnapshot(groupSpecs, fields, sections, diagnostics, true);
    }

    public List<String> groups() {
        return groupSpecs.stream()
                .map(ConfigGroupSpec::label)
                .toList();
    }

    public List<ConfigGroupSpec> groupSpecs() {
        return groupSpecs;
    }

    public List<ConfigFieldSpec> fields() {
        return fields;
    }

    /**
     * Trusted key-to-field index captured with this immutable snapshot.
     * <p>
     * The indexed {@link ConfigFieldSpec} retains both the host-stamped owner and the effective field type.
     * Ambiguous duplicate keys are omitted from this map instead of selecting either declaration.
     */
    public Map<String, ConfigFieldSpec> fieldsByKey() {
        return fieldsByKey;
    }

    public ConfigFieldSpec field(String key) {
        return key == null ? null : fieldsByKey.get(key);
    }

    public List<GuiConfigSectionSpec> sections() {
        return sections;
    }

    public List<GuiConfigContributionDiagnostic> diagnostics() {
        return diagnostics;
    }

    private static List<ConfigGroupSpec> toGroupSpecs(List<String> groups) {
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }
        List<ConfigGroupSpec> specs = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            String label = groups.get(i);
            int order = (i + 1) * 100;
            ConfigGroupSpec spec = ConfigFieldRegistry.coreGroupSpecByLabel(label)
                    .orElseGet(() -> new ConfigGroupSpec(null, label, order, true));
            specs.add(spec);
        }
        return List.copyOf(specs);
    }

    private static Map<String, ConfigFieldSpec> indexFields(List<ConfigFieldSpec> fields) {
        Map<String, ConfigFieldSpec> indexed = new LinkedHashMap<>();
        java.util.Set<String> ambiguous = new java.util.HashSet<>();
        for (ConfigFieldSpec field : fields) {
            if (field == null || field.key() == null || field.key().isBlank() || ambiguous.contains(field.key())) {
                continue;
            }
            if (indexed.putIfAbsent(field.key(), field) != null) {
                indexed.remove(field.key());
                ambiguous.add(field.key());
            }
        }
        return Collections.unmodifiableMap(indexed);
    }
}
