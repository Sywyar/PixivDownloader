package top.sywyar.pixivdownload.plugin.api.gui;

/** 从当前字段值推断所选 GUI 配置预设时使用的匹配模式。 */
public enum GuiConfigPresetMatchMode {
    /** 忽略大小写进行相等比较。 */
    EQUALS_IGNORE_CASE,
    /** 移除末尾斜杠后忽略大小写进行相等比较。 */
    TRIMMED_TRAILING_SLASH_IGNORE_CASE
}
