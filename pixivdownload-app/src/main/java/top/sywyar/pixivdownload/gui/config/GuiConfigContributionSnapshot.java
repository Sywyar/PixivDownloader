package top.sywyar.pixivdownload.gui.config;

import java.util.List;

/**
 * Plugin-contributed GUI configuration groups, fields, rich sections and diagnostics after host aggregation.
 */
public record GuiConfigContributionSnapshot(
        List<ConfigGroupSpec> groups,
        List<ConfigFieldSpec> fields,
        List<GuiConfigSectionSpec> sections,
        List<GuiConfigContributionDiagnostic> diagnostics
) {

    public GuiConfigContributionSnapshot {
        groups = groups == null ? List.of() : List.copyOf(groups);
        fields = fields == null ? List.of() : List.copyOf(fields);
        sections = sections == null ? List.of() : List.copyOf(sections);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public GuiConfigContributionSnapshot(List<ConfigGroupSpec> groups,
                                         List<ConfigFieldSpec> fields,
                                         List<GuiConfigContributionDiagnostic> diagnostics) {
        this(groups, fields, List.of(), diagnostics);
    }

    public static GuiConfigContributionSnapshot empty() {
        return new GuiConfigContributionSnapshot(List.of(), List.of(), List.of(), List.of());
    }
}
