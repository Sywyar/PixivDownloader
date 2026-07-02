package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * One field-to-payload mapping for a GUI configuration action.
 *
 * @param payloadPath dot-separated JSON path written into the request body
 * @param fieldKey optional config field key whose current GUI value is copied
 * @param literalValue optional literal value used when {@code fieldKey} is blank
 * @param valueType value coercion to apply before writing the payload
 */
public record GuiConfigActionPayloadField(
        String payloadPath,
        String fieldKey,
        String literalValue,
        GuiConfigActionPayloadType valueType
) {

    public GuiConfigActionPayloadField {
        payloadPath = blankToNull(payloadPath);
        fieldKey = blankToNull(fieldKey);
        literalValue = literalValue == null ? "" : literalValue;
        valueType = valueType == null ? GuiConfigActionPayloadType.STRING : valueType;
    }

    public GuiConfigActionPayloadField(String payloadPath, String fieldKey,
                                       GuiConfigActionPayloadType valueType) {
        this(payloadPath, fieldKey, "", valueType);
    }

    public GuiConfigActionPayloadField(String payloadPath, String fieldKey) {
        this(payloadPath, fieldKey, "", GuiConfigActionPayloadType.STRING);
    }

    public static GuiConfigActionPayloadField literal(String payloadPath, String value,
                                                      GuiConfigActionPayloadType valueType) {
        return new GuiConfigActionPayloadField(payloadPath, null, value, valueType);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
