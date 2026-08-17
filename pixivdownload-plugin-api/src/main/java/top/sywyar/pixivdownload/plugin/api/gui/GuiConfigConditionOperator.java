package top.sywyar.pixivdownload.plugin.api.gui;

/** 字段启用或可见条件使用的纯数据运算符。 */
public enum GuiConfigConditionOperator {
    /** 值为真。 */
    TRUE,
    /** 值为假。 */
    FALSE,
    /** 值相等。 */
    EQUALS,
    /** 值不相等。 */
    NOT_EQUALS,
    /** 值为空白。 */
    BLANK,
    /** 值不为空白。 */
    NOT_BLANK
}
