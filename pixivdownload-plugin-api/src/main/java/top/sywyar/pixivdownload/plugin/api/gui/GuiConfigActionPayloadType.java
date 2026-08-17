package top.sywyar.pixivdownload.plugin.api.gui;

/** GUI 配置动作把字段值复制到请求体时使用的值转换类型。 */
public enum GuiConfigActionPayloadType {
    /** 转换为字符串。 */
    STRING,
    /** 转换为整数。 */
    INT,
    /** 转换为布尔值。 */
    BOOLEAN
}
