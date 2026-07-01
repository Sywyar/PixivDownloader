package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.List;

/**
 * Pure data contribution for GUI configuration groups and fields.
 *
 * @param groups optional custom group metadata
 * @param fields field declarations contributed by the plugin
 */
public record GuiConfigContribution(
        List<GuiConfigGroupContribution> groups,
        List<GuiConfigFieldContribution> fields
) {

    public GuiConfigContribution {
        groups = groups == null ? List.of() : List.copyOf(groups);
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public GuiConfigContribution(List<GuiConfigFieldContribution> fields) {
        this(List.of(), fields);
    }
}
