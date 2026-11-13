package top.sywyar.pixivdownload.plugin.api.gui;

/** 丰富 GUI 配置 section 的内置布局提示。 */
public enum GuiConfigSectionLayout {
    /** 按声明顺序把字段渲染为一个纵向表单。 */
    FIELD_LIST,
    /** 按卡片 ID 分组字段，并通过 section 内切换器展示。 */
    CARD_SWITCHER,
    /** 保持 section 顺序，把紧凑的类布尔控件渲染为网格。 */
    COMPACT_GRID,
    /** 首字段为默认目录模板，其余为可选覆盖；空覆盖继承默认值，移除只清空对应字段。 */
    DIRECTORY_TEMPLATES
}
