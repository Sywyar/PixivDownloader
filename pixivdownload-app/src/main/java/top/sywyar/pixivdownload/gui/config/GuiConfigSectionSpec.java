package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        boolean contributesGroupVisibility,
        Set<String> ownerPluginIds
) {

    public GuiConfigSectionSpec {
        notices = notices == null ? List.of() : List.copyOf(notices);
        fieldLayouts = fieldLayouts == null ? List.of() : List.copyOf(fieldLayouts);
        actions = actions == null ? List.of() : List.copyOf(actions);
        presets = presets == null ? List.of() : List.copyOf(presets);
        LinkedHashSet<String> owners = new LinkedHashSet<>();
        if (ownerPluginIds != null) {
            ownerPluginIds.stream()
                    .filter(owner -> owner != null && !owner.isBlank())
                    .map(String::trim)
                    .forEach(owners::add);
        }
        if (pluginId != null && !pluginId.isBlank()) {
            owners.add(pluginId.trim());
        }
        ownerPluginIds = Set.copyOf(owners);
    }

    public GuiConfigSectionSpec(String pluginId, String sectionId, String groupId, String group,
                                int groupOrder, String title, String help, String layoutLabel,
                                String layoutHelp, String presetLabel, String presetHelp,
                                List<GuiConfigSectionNoticeSpec> notices, GuiConfigSectionLayout layout,
                                int order, List<GuiConfigFieldLayoutSpec> fieldLayouts,
                                List<GuiConfigActionSpec> actions, List<GuiConfigPresetSpec> presets,
                                boolean mergeable, boolean contributesGroupVisibility) {
        this(pluginId, sectionId, groupId, group, groupOrder, title, help, layoutLabel, layoutHelp,
                presetLabel, presetHelp, notices, layout, order, fieldLayouts, actions, presets,
                mergeable, contributesGroupVisibility,
                pluginId == null || pluginId.isBlank() ? Set.of() : Set.of(pluginId.trim()));
    }
}
