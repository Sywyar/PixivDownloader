package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.List;

/**
 * Pure data declaration for a rich GUI configuration section.
 *
 * @param sectionId stable section id, unique within the GUI config contribution snapshot
 * @param groupId stable target group id; built-in ids are available from {@link GuiConfigGroups}
 * @param titleKey optional i18n key for a section title inside the group
 * @param helpKey optional i18n key for section help text
 * @param i18nNamespace optional i18n namespace; blank means the plugin display namespace
 * @param layout section layout hint
 * @param order section ordering hint inside its group
 * @param fieldLayouts optional field layout metadata
 * @param actions optional test or probe actions
 * @param presets optional value presets
 */
public record GuiConfigSectionContribution(
        String sectionId,
        String groupId,
        String titleKey,
        String helpKey,
        String i18nNamespace,
        GuiConfigSectionLayout layout,
        int order,
        List<GuiConfigFieldLayoutContribution> fieldLayouts,
        List<GuiConfigActionContribution> actions,
        List<GuiConfigPresetContribution> presets
) {

    public GuiConfigSectionContribution {
        titleKey = titleKey == null ? "" : titleKey;
        helpKey = helpKey == null ? "" : helpKey;
        i18nNamespace = blankToNull(i18nNamespace);
        layout = layout == null ? GuiConfigSectionLayout.FIELD_LIST : layout;
        fieldLayouts = fieldLayouts == null ? List.of() : List.copyOf(fieldLayouts);
        actions = actions == null ? List.of() : List.copyOf(actions);
        presets = presets == null ? List.of() : List.copyOf(presets);
    }

    public GuiConfigSectionContribution(String sectionId, String groupId,
                                        GuiConfigSectionLayout layout, int order) {
        this(sectionId, groupId, "", "", null, layout, order, List.of(), List.of(), List.of());
    }

    public GuiConfigSectionContribution(String sectionId, String groupId,
                                        GuiConfigSectionLayout layout, int order,
                                        List<GuiConfigFieldLayoutContribution> fieldLayouts) {
        this(sectionId, groupId, "", "", null, layout, order, fieldLayouts, List.of(), List.of());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
