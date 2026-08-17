package top.sywyar.pixivdownload.plugin.api.gui;

/** 声明式 GUI 配置动作结果规则使用的运算符。 */
public enum GuiConfigActionResultOperator {
    /** 值为真。 */
    TRUE,
    /** 值为假。 */
    FALSE,
    /** 值相等。 */
    EQUALS,
    /** 值不相等。 */
    NOT_EQUALS,
    /** 数值大于比较值。 */
    GREATER_THAN,
    /** 文本中含有比较值。 */
    CONTAINS,
    /** 值为空白。 */
    BLANK,
    /** 值不为空白。 */
    NOT_BLANK
}
