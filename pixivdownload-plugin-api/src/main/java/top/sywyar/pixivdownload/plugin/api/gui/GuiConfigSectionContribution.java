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
 * @param layoutLabelKey optional i18n key for the layout control label
 * @param layoutHelpKey optional i18n key for the layout control help text
 * @param presetLabelKey optional i18n key for the preset control label
 * @param presetHelpKey optional i18n key for the preset control help text
 * @param notices optional section-level notices rendered before fields and cards
 * @param layout section layout hint
 * @param order section ordering hint inside its group
 * @param fieldLayouts optional field layout metadata
 * @param actions optional test or probe actions
 * @param presets optional value presets
 * @param mergeable whether sections with the same id from multiple plugins may be merged
 * @param contributesGroupVisibility whether this section alone should make its group visible in tabs
 */
public record GuiConfigSectionContribution(
        String sectionId,
        String groupId,
        String titleKey,
        String helpKey,
        String i18nNamespace,
        String layoutLabelKey,
        String layoutHelpKey,
        String presetLabelKey,
        String presetHelpKey,
        List<GuiConfigSectionNoticeContribution> notices,
        GuiConfigSectionLayout layout,
        int order,
        List<GuiConfigFieldLayoutContribution> fieldLayouts,
        List<GuiConfigActionContribution> actions,
        List<GuiConfigPresetContribution> presets,
        boolean mergeable,
        boolean contributesGroupVisibility
) {

    public GuiConfigSectionContribution {
        titleKey = titleKey == null ? "" : titleKey;
        helpKey = helpKey == null ? "" : helpKey;
        i18nNamespace = blankToNull(i18nNamespace);
        layoutLabelKey = layoutLabelKey == null ? "" : layoutLabelKey;
        layoutHelpKey = layoutHelpKey == null ? "" : layoutHelpKey;
        presetLabelKey = presetLabelKey == null ? "" : presetLabelKey;
        presetHelpKey = presetHelpKey == null ? "" : presetHelpKey;
        notices = notices == null ? List.of() : List.copyOf(notices);
        layout = layout == null ? GuiConfigSectionLayout.FIELD_LIST : layout;
        fieldLayouts = fieldLayouts == null ? List.of() : List.copyOf(fieldLayouts);
        actions = actions == null ? List.of() : List.copyOf(actions);
        presets = presets == null ? List.of() : List.copyOf(presets);
    }

    public GuiConfigSectionContribution(String sectionId, String groupId,
                                        GuiConfigSectionLayout layout, int order) {
        this(sectionId, groupId, "", "", null, "", "", "", "", List.of(), layout, order,
                List.of(), List.of(), List.of(), false, true);
    }

    public GuiConfigSectionContribution(String sectionId, String groupId,
                                        GuiConfigSectionLayout layout, int order,
                                        List<GuiConfigFieldLayoutContribution> fieldLayouts) {
        this(sectionId, groupId, "", "", null, "", "", "", "", List.of(), layout, order,
                fieldLayouts, List.of(), List.of(), false, true);
    }

    public GuiConfigSectionContribution(String sectionId, String groupId, String titleKey,
                                        String helpKey, String i18nNamespace,
                                        GuiConfigSectionLayout layout, int order,
                                        List<GuiConfigFieldLayoutContribution> fieldLayouts,
                                        List<GuiConfigActionContribution> actions,
                                        List<GuiConfigPresetContribution> presets) {
        this(sectionId, groupId, titleKey, helpKey, i18nNamespace, "", "", "", "", List.of(),
                layout, order, fieldLayouts, actions, presets, false, true);
    }

    public GuiConfigSectionContribution(String sectionId, String groupId, String titleKey,
                                        String helpKey, String i18nNamespace,
                                        String layoutLabelKey, String layoutHelpKey,
                                        String presetLabelKey, String presetHelpKey,
                                        List<GuiConfigSectionNoticeContribution> notices,
                                        GuiConfigSectionLayout layout, int order,
                                        List<GuiConfigFieldLayoutContribution> fieldLayouts,
                                        List<GuiConfigActionContribution> actions,
                                        List<GuiConfigPresetContribution> presets) {
        this(sectionId, groupId, titleKey, helpKey, i18nNamespace,
                layoutLabelKey, layoutHelpKey, presetLabelKey, presetHelpKey, notices,
                layout, order, fieldLayouts, actions, presets, false, true);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
