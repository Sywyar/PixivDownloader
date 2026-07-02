package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * Layout metadata for a contributed GUI configuration field.
 *
 * @param fieldKey config field key being placed
 * @param cardId optional card id for card-switcher layouts
 * @param cardLabelKey optional i18n key for the card label
 * @param i18nNamespace optional i18n namespace; blank means the plugin display namespace
 * @param order field ordering hint inside its layout container
 */
public record GuiConfigFieldLayoutContribution(
        String fieldKey,
        String cardId,
        String cardLabelKey,
        String i18nNamespace,
        int order
) {

    public GuiConfigFieldLayoutContribution {
        cardId = blankToNull(cardId);
        cardLabelKey = cardLabelKey == null ? "" : cardLabelKey;
        i18nNamespace = blankToNull(i18nNamespace);
    }

    public GuiConfigFieldLayoutContribution(String fieldKey, int order) {
        this(fieldKey, null, "", null, order);
    }

    public GuiConfigFieldLayoutContribution(String fieldKey, String cardId, String cardLabelKey, int order) {
        this(fieldKey, cardId, cardLabelKey, null, order);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
