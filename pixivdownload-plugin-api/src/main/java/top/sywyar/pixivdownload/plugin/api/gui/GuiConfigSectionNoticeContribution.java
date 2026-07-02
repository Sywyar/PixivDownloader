package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * Pure data declaration for a short notice rendered at the top of a GUI config section.
 *
 * @param noticeId stable id used to merge duplicate notices from multiple plugins
 * @param textKey i18n key for the notice text
 * @param i18nNamespace optional i18n namespace; blank means the section plugin display namespace
 * @param style host-neutral visual style
 * @param order ordering hint inside the section
 */
public record GuiConfigSectionNoticeContribution(
        String noticeId,
        String textKey,
        String i18nNamespace,
        GuiConfigSectionNoticeStyle style,
        int order
) {

    public GuiConfigSectionNoticeContribution {
        noticeId = noticeId == null ? "" : noticeId.trim();
        textKey = textKey == null ? "" : textKey;
        i18nNamespace = blankToNull(i18nNamespace);
        style = style == null ? GuiConfigSectionNoticeStyle.HINT : style;
    }

    public GuiConfigSectionNoticeContribution(String noticeId, String textKey, int order) {
        this(noticeId, textKey, null, GuiConfigSectionNoticeStyle.HINT, order);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
