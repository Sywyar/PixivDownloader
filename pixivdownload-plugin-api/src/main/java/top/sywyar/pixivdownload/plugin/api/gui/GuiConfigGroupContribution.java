package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * Optional GUI configuration group metadata contributed by a plugin.
 *
 * @param groupId        stable group id used by fields
 * @param labelKey       i18n key for the group label
 * @param i18nNamespace  optional i18n namespace; blank means the plugin display namespace
 * @param order          group ordering hint
 * @param visibleInTabs  whether the group should appear as its own configuration tab
 */
public record GuiConfigGroupContribution(
        String groupId,
        String labelKey,
        String i18nNamespace,
        int order,
        boolean visibleInTabs
) {

    public GuiConfigGroupContribution {
        i18nNamespace = blankToNull(i18nNamespace);
    }

    public GuiConfigGroupContribution(String groupId, String labelKey, int order) {
        this(groupId, labelKey, null, order, true);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
