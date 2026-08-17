package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.List;

/**
 * 丰富 GUI 配置 section 的纯数据声明。
 *
 * @param sectionId 稳定 section ID，在 GUI 配置贡献快照内唯一
 * @param groupId 稳定目标分组 ID；内置 ID 见 {@link GuiConfigGroups}
 * @param titleKey 分组内 section 标题的可选 i18n key
 * @param helpKey section 帮助文本的可选 i18n key
 * @param i18nNamespace 可选的 i18n namespace；空白表示插件展示 namespace
 * @param layoutLabelKey 布局控件标签的可选 i18n key
 * @param layoutHelpKey 布局控件帮助文本的可选 i18n key
 * @param presetLabelKey 预设控件标签的可选 i18n key
 * @param presetHelpKey 预设控件帮助文本的可选 i18n key
 * @param notices 在字段和卡片之前渲染的可选 section 级提示
 * @param layout section 布局提示
 * @param order section 在所属分组内的排序提示
 * @param fieldLayouts 可选字段布局元数据
 * @param actions 可选测试或探测动作
 * @param presets 可选值预设
 * @param mergeable 是否允许合并多个插件贡献的同 ID section
 * @param contributesGroupVisibility 是否仅凭该 section 就让所属分组显示为标签页
 */
public record GuiConfigSectionContribution(
        String sectionId,
        String groupId,
        String titleKey,
        String helpKey,
        String i18nNamespace,
        String layoutLabelKey,
        String layoutHelpKey,
        String presetLabelKey,
        String presetHelpKey,
        List<GuiConfigSectionNoticeContribution> notices,
        GuiConfigSectionLayout layout,
        int order,
        List<GuiConfigFieldLayoutContribution> fieldLayouts,
        List<GuiConfigActionContribution> actions,
        List<GuiConfigPresetContribution> presets,
        boolean mergeable,
        boolean contributesGroupVisibility
) {

    /**
     * 规范化可选文本、布局和集合。
     *
     * @param sectionId 区段标识
     * @param groupId 分组标识
     * @param titleKey 标题键
     * @param helpKey 帮助文本键
     * @param i18nNamespace 国际化命名空间
     * @param layoutLabelKey 布局标签键
     * @param layoutHelpKey 布局帮助文本键
     * @param presetLabelKey 预设标签键
     * @param presetHelpKey 预设帮助文本键
     * @param notices 通知列表
     * @param layout 布局
     * @param order 排序值
     * @param fieldLayouts 字段布局列表
     * @param actions 操作列表
     * @param presets 预设列表
     * @param mergeable 是否可合并
     * @param contributesGroupVisibility 贡献状态分组可见性
     */
    public GuiConfigSectionContribution {
        titleKey = titleKey == null ? "" : titleKey;
        helpKey = helpKey == null ? "" : helpKey;
        i18nNamespace = blankToNull(i18nNamespace);
        layoutLabelKey = layoutLabelKey == null ? "" : layoutLabelKey;
        layoutHelpKey = layoutHelpKey == null ? "" : layoutHelpKey;
        presetLabelKey = presetLabelKey == null ? "" : presetLabelKey;
        presetHelpKey = presetHelpKey == null ? "" : presetHelpKey;
        notices = notices == null ? List.of() : List.copyOf(notices);
        layout = layout == null ? GuiConfigSectionLayout.FIELD_LIST : layout;
        fieldLayouts = fieldLayouts == null ? List.of() : List.copyOf(fieldLayouts);
        actions = actions == null ? List.of() : List.copyOf(actions);
        presets = presets == null ? List.of() : List.copyOf(presets);
    }

    /**
     * 创建不含字段元数据、动作和预设的基础 section。
     *
     * @param sectionId 稳定 section ID
     * @param groupId 稳定目标分组 ID
     * @param layout section 布局提示
     * @param order 排序提示
     */
    public GuiConfigSectionContribution(String sectionId, String groupId,
                                        GuiConfigSectionLayout layout, int order) {
        this(sectionId, groupId, "", "", null, "", "", "", "", List.of(), layout, order,
                List.of(), List.of(), List.of(), false, true);
    }

    /**
     * 创建只包含字段布局元数据的 section。
     *
     * @param sectionId 稳定 section ID
     * @param groupId 稳定目标分组 ID
     * @param layout section 布局提示
     * @param order 排序提示
     * @param fieldLayouts 字段布局元数据
     */
    public GuiConfigSectionContribution(String sectionId, String groupId,
                                        GuiConfigSectionLayout layout, int order,
                                        List<GuiConfigFieldLayoutContribution> fieldLayouts) {
        this(sectionId, groupId, "", "", null, "", "", "", "", List.of(), layout, order,
                fieldLayouts, List.of(), List.of(), false, true);
    }

    /**
     * 创建带标题、帮助、字段布局、动作和预设的 section。
     *
     * @param sectionId 稳定 section ID
     * @param groupId 稳定目标分组 ID
     * @param titleKey section 标题 i18n key
     * @param helpKey section 帮助文本 i18n key
     * @param i18nNamespace 可选的 i18n namespace
     * @param layout section 布局提示
     * @param order 排序提示
     * @param fieldLayouts 字段布局元数据
     * @param actions 测试或探测动作
     * @param presets 值预设
     */
    public GuiConfigSectionContribution(String sectionId, String groupId, String titleKey,
                                        String helpKey, String i18nNamespace,
                                        GuiConfigSectionLayout layout, int order,
                                        List<GuiConfigFieldLayoutContribution> fieldLayouts,
                                        List<GuiConfigActionContribution> actions,
                                        List<GuiConfigPresetContribution> presets) {
        this(sectionId, groupId, titleKey, helpKey, i18nNamespace, "", "", "", "", List.of(),
                layout, order, fieldLayouts, actions, presets, false, true);
    }

    /**
     * 创建带完整控件标签、提示、动作和预设的 section。
     *
     * @param sectionId 稳定 section ID
     * @param groupId 稳定目标分组 ID
     * @param titleKey section 标题 i18n key
     * @param helpKey section 帮助文本 i18n key
     * @param i18nNamespace 可选的 i18n namespace
     * @param layoutLabelKey 布局控件标签 i18n key
     * @param layoutHelpKey 布局控件帮助文本 i18n key
     * @param presetLabelKey 预设控件标签 i18n key
     * @param presetHelpKey 预设控件帮助文本 i18n key
     * @param notices section 级提示
     * @param layout section 布局提示
     * @param order 排序提示
     * @param fieldLayouts 字段布局元数据
     * @param actions 测试或探测动作
     * @param presets 值预设
     */
    public GuiConfigSectionContribution(String sectionId, String groupId, String titleKey,
                                        String helpKey, String i18nNamespace,
                                        String layoutLabelKey, String layoutHelpKey,
                                        String presetLabelKey, String presetHelpKey,
                                        List<GuiConfigSectionNoticeContribution> notices,
                                        GuiConfigSectionLayout layout, int order,
                                        List<GuiConfigFieldLayoutContribution> fieldLayouts,
                                        List<GuiConfigActionContribution> actions,
                                        List<GuiConfigPresetContribution> presets) {
        this(sectionId, groupId, titleKey, helpKey, i18nNamespace,
                layoutLabelKey, layoutHelpKey, presetLabelKey, presetHelpKey, notices,
                layout, order, fieldLayouts, actions, presets, false, true);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
