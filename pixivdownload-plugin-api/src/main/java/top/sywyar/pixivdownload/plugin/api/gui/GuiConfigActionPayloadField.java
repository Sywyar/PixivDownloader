package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * GUI 配置动作的一项字段到请求体映射。
 *
 * @param payloadPath 写入请求体的点分隔 JSON 路径
 * @param fieldKey 当前 GUI 值会被复制的可选配置字段 key；宿主只接受与动作属于同一插件的字段
 * @param literalValue {@code fieldKey} 为空白时使用的可选字面值
 * @param valueType 写入请求体前应用的值转换类型
 */
public record GuiConfigActionPayloadField(
        String payloadPath,
        String fieldKey,
        String literalValue,
        GuiConfigActionPayloadType valueType
) {

    /**
     * 规范化路径、字段 key、字面值和值类型。
     *
     * @param payloadPath 载荷路径
     * @param fieldKey 字段键
     * @param literalValue 字面值
     * @param valueType 值类型
     */
    public GuiConfigActionPayloadField {
        payloadPath = blankToNull(payloadPath);
        fieldKey = blankToNull(fieldKey);
        literalValue = literalValue == null ? "" : literalValue;
        valueType = valueType == null ? GuiConfigActionPayloadType.STRING : valueType;
    }

    /**
     * 创建从配置字段读取值的映射。
     *
     * @param payloadPath 写入请求体的点分隔 JSON 路径
     * @param fieldKey 配置字段 key
     * @param valueType 写入前应用的值转换类型
     */
    public GuiConfigActionPayloadField(String payloadPath, String fieldKey,
                                       GuiConfigActionPayloadType valueType) {
        this(payloadPath, fieldKey, "", valueType);
    }

    /**
     * 创建按字符串复制配置字段值的映射。
     *
     * @param payloadPath 写入请求体的点分隔 JSON 路径
     * @param fieldKey 配置字段 key
     */
    public GuiConfigActionPayloadField(String payloadPath, String fieldKey) {
        this(payloadPath, fieldKey, "", GuiConfigActionPayloadType.STRING);
    }

    /**
     * 创建写入固定字面值的映射。
     *
     * @param payloadPath 写入请求体的点分隔 JSON 路径
     * @param value 固定字面值
     * @param valueType 写入前应用的值转换类型
     * @return 字面值映射
     */
    public static GuiConfigActionPayloadField literal(String payloadPath, String value,
                                                      GuiConfigActionPayloadType valueType) {
        return new GuiConfigActionPayloadField(payloadPath, null, value, valueType);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
