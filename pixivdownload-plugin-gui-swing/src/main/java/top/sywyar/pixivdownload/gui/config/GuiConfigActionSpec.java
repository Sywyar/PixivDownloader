package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadField;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultSummary;

import java.util.List;

/**
 * Resolved GUI configuration action metadata ready for Swing rendering.
 */
public record GuiConfigActionSpec(
        String actionId,
        String label,
        String help,
        String cardId,
        String endpoint,
        int readTimeoutMillis,
        int order,
        List<GuiConfigActionPayloadField> payloadFields,
        String sendingNotice,
        List<GuiConfigActionResultRuleSpec> resultRules,
        GuiConfigActionResultSummary resultSummary,
        String ownerPluginId
) {

    public GuiConfigActionSpec {
        payloadFields = payloadFields == null ? List.of() : List.copyOf(payloadFields);
        sendingNotice = sendingNotice == null ? "" : sendingNotice;
        resultRules = resultRules == null ? List.of() : List.copyOf(resultRules);
    }

    public GuiConfigActionSpec(String actionId, String label, String help, String cardId,
                               String endpoint, int readTimeoutMillis, int order,
                               List<GuiConfigActionPayloadField> payloadFields, String sendingNotice,
                               List<GuiConfigActionResultRuleSpec> resultRules,
                               GuiConfigActionResultSummary resultSummary) {
        this(actionId, label, help, cardId, endpoint, readTimeoutMillis, order, payloadFields,
                sendingNotice, resultRules, resultSummary, null);
    }
}
