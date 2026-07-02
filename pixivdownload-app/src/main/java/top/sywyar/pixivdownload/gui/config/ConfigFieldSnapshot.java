package top.sywyar.pixivdownload.gui.config;

import java.util.List;

/**
 * Immutable field, group and rich section snapshot consumed by {@code ConfigPanel}.
 */
public record ConfigFieldSnapshot(
        List<String> groups,
        List<ConfigFieldSpec> fields,
        List<GuiConfigSectionSpec> sections,
        List<GuiConfigContributionDiagnostic> diagnostics
) {

    public ConfigFieldSnapshot {
        groups = groups == null ? List.of() : List.copyOf(groups);
        fields = fields == null ? List.of() : List.copyOf(fields);
        sections = sections == null ? List.of() : List.copyOf(sections);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public ConfigFieldSnapshot(List<String> groups,
                               List<ConfigFieldSpec> fields,
                               List<GuiConfigContributionDiagnostic> diagnostics) {
        this(groups, fields, List.of(), diagnostics);
    }
}
