package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadField;

import java.util.List;

/**
 * Resolved GUI configuration action metadata ready for Swing rendering.
 */
public record GuiConfigActionSpec(
        String actionId,
        String label,
        String help,
        String endpoint,
        int readTimeoutMillis,
        int order,
        List<GuiConfigActionPayloadField> payloadFields
) {

    public GuiConfigActionSpec {
        payloadFields = payloadFields == null ? List.of() : List.copyOf(payloadFields);
    }
}
