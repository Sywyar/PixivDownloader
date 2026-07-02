package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.List;

/**
 * Pure data contribution for GUI configuration groups, fields and rich section metadata.
 *
 * @param groups optional custom group metadata
 * @param fields field declarations contributed by the plugin
 * @param sections optional rich section declarations for layout, actions and presets
 */
public record GuiConfigContribution(
        List<GuiConfigGroupContribution> groups,
        List<GuiConfigFieldContribution> fields,
        List<GuiConfigSectionContribution> sections
) {

    public GuiConfigContribution {
        groups = groups == null ? List.of() : List.copyOf(groups);
        fields = fields == null ? List.of() : List.copyOf(fields);
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    public GuiConfigContribution(List<GuiConfigGroupContribution> groups,
                                 List<GuiConfigFieldContribution> fields) {
        this(groups, fields, List.of());
    }

    public GuiConfigContribution(List<GuiConfigFieldContribution> fields) {
        this(List.of(), fields, List.of());
    }
}
