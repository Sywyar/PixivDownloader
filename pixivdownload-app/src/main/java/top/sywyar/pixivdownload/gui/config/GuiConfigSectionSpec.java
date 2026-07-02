package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;

import java.util.List;

/**
 * Resolved rich GUI configuration section metadata ready for host-side section factories.
 */
public record GuiConfigSectionSpec(
        String pluginId,
        String sectionId,
        String groupId,
        String group,
        int groupOrder,
        String title,
        String help,
        GuiConfigSectionLayout layout,
        int order,
        List<GuiConfigFieldLayoutSpec> fieldLayouts,
        List<GuiConfigActionSpec> actions,
        List<GuiConfigPresetSpec> presets
) {

    public GuiConfigSectionSpec {
        fieldLayouts = fieldLayouts == null ? List.of() : List.copyOf(fieldLayouts);
        actions = actions == null ? List.of() : List.copyOf(actions);
        presets = presets == null ? List.of() : List.copyOf(presets);
    }
}
