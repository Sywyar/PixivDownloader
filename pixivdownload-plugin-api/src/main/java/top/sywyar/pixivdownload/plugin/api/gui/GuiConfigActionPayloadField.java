package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * One field-to-payload mapping for a GUI configuration action.
 *
 * @param payloadPath dot-separated JSON path written into the request body
 * @param fieldKey config field key whose current GUI value is copied
 * @param valueType value coercion to apply before writing the payload
 */
public record GuiConfigActionPayloadField(
        String payloadPath,
        String fieldKey,
        GuiConfigActionPayloadType valueType
) {

    public GuiConfigActionPayloadField {
        payloadPath = blankToNull(payloadPath);
        fieldKey = blankToNull(fieldKey);
        valueType = valueType == null ? GuiConfigActionPayloadType.STRING : valueType;
    }

    public GuiConfigActionPayloadField(String payloadPath, String fieldKey) {
        this(payloadPath, fieldKey, GuiConfigActionPayloadType.STRING);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
