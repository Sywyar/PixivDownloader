package top.sywyar.pixivdownload.gui.config;

import java.util.List;

/**
 * Immutable field and group snapshot consumed by {@code ConfigPanel}.
 */
public record ConfigFieldSnapshot(
        List<String> groups,
        List<ConfigFieldSpec> fields,
        List<GuiConfigContributionDiagnostic> diagnostics
) {

    public ConfigFieldSnapshot {
        groups = groups == null ? List.of() : List.copyOf(groups);
        fields = fields == null ? List.of() : List.copyOf(fields);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
