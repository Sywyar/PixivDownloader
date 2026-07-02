package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.List;

/**
 * Pure data declaration for a GUI configuration action, such as a connectivity or send-test button.
 *
 * @param actionId stable action id inside the section
 * @param labelKey i18n key for the button label
 * @param helpKey optional i18n key for help text
 * @param i18nNamespace optional i18n namespace; blank means the plugin display namespace
 * @param cardId optional card id for card-switcher layouts
 * @param endpoint GUI API endpoint relative to {@code /api/gui/}
 * @param readTimeoutMillis HTTP read timeout for this action
 * @param order action ordering hint inside the section
 * @param payloadFields field values copied into the action request payload
 * @param sendingNoticeKey optional i18n key shown while the action is running
 * @param resultRules optional response notice rules; first matching rule wins
 * @param resultSummary optional response array summary used by result rules
 */
public record GuiConfigActionContribution(
        String actionId,
        String labelKey,
        String helpKey,
        String i18nNamespace,
        String cardId,
        String endpoint,
        int readTimeoutMillis,
        int order,
        List<GuiConfigActionPayloadField> payloadFields,
        String sendingNoticeKey,
        List<GuiConfigActionResultRule> resultRules,
        GuiConfigActionResultSummary resultSummary
) {

    public GuiConfigActionContribution {
        helpKey = helpKey == null ? "" : helpKey;
        i18nNamespace = blankToNull(i18nNamespace);
        cardId = blankToNull(cardId);
        endpoint = endpoint == null ? "" : endpoint.trim();
        payloadFields = payloadFields == null ? List.of() : List.copyOf(payloadFields);
        sendingNoticeKey = sendingNoticeKey == null ? "" : sendingNoticeKey;
        resultRules = resultRules == null ? List.of() : List.copyOf(resultRules);
    }

    public GuiConfigActionContribution(String actionId, String labelKey, String endpoint, int order,
                                       List<GuiConfigActionPayloadField> payloadFields) {
        this(actionId, labelKey, "", null, null, endpoint, 30_000, order, payloadFields,
                "", List.of(), null);
    }

    public GuiConfigActionContribution(String actionId, String labelKey, String helpKey, String i18nNamespace,
                                       String endpoint, int readTimeoutMillis, int order,
                                       List<GuiConfigActionPayloadField> payloadFields) {
        this(actionId, labelKey, helpKey, i18nNamespace, null, endpoint, readTimeoutMillis, order,
                payloadFields, "", List.of(), null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
