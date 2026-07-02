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
        String layoutLabel,
        String layoutHelp,
        String presetLabel,
        String presetHelp,
        List<GuiConfigSectionNoticeSpec> notices,
        GuiConfigSectionLayout layout,
        int order,
        List<GuiConfigFieldLayoutSpec> fieldLayouts,
        List<GuiConfigActionSpec> actions,
        List<GuiConfigPresetSpec> presets,
        boolean mergeable,
        boolean contributesGroupVisibility
) {

    public GuiConfigSectionSpec {
        notices = notices == null ? List.of() : List.copyOf(notices);
        fieldLayouts = fieldLayouts == null ? List.of() : List.copyOf(fieldLayouts);
        actions = actions == null ? List.of() : List.copyOf(actions);
        presets = presets == null ? List.of() : List.copyOf(presets);
    }
}
