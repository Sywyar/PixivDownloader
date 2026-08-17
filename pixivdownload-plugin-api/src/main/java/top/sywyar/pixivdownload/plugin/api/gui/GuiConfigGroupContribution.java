package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * 插件贡献的可选 GUI 配置分组元数据。
 *
 * @param groupId 字段使用的稳定分组 ID
 * @param labelKey 分组标签的 i18n key
 * @param i18nNamespace 可选的 i18n namespace；空白表示插件展示 namespace
 * @param order 分组排序提示
 * @param visibleInTabs 分组是否显示为独立配置标签页
 */
public record GuiConfigGroupContribution(
        String groupId,
        String labelKey,
        String i18nNamespace,
        int order,
        boolean visibleInTabs
) {

    /**
     * 规范化 i18n namespace。
     *
     * @param groupId 分组标识
     * @param labelKey 标签键
     * @param i18nNamespace 国际化命名空间
     * @param order 排序值
     * @param visibleInTabs 是否在标签页中显示
     */
    public GuiConfigGroupContribution {
        i18nNamespace = blankToNull(i18nNamespace);
    }

    /**
     * 创建使用插件展示 namespace 且显示为标签页的分组。
     *
     * @param groupId 稳定分组 ID
     * @param labelKey 分组标签 i18n key
     * @param order 分组排序提示
     */
    public GuiConfigGroupContribution(String groupId, String labelKey, int order) {
        this(groupId, labelKey, null, order, true);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
