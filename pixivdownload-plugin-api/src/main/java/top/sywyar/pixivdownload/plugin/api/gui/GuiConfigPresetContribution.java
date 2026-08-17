package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GUI 配置预设的纯数据声明。
 *
 * @param presetId section 内稳定的预设 ID
 * @param labelKey 预设标签的 i18n key
 * @param helpKey 可选的帮助文本 i18n key
 * @param i18nNamespace 可选的 i18n namespace；空白表示插件展示 namespace
 * @param cardId 卡片切换布局使用的可选卡片 ID
 * @param order 预设排序提示
 * @param matchFieldKey 用于从当前值推断选中预设的可选同 owner 非敏感字段
 * @param matchValue 与 {@code matchFieldKey} 匹配的可选值
 * @param values 选择预设时应用的同 owner 非敏感配置字段值；预设永远不能写入敏感字段和
 *               {@link GuiConfigFieldType#PASSWORD} 字段
 * @param lockedFieldKeys 选中该预设时锁定的同 owner 非敏感配置字段 key；{@code null} 表示全部 value key
 * @param matchMode {@code matchFieldKey}/{@code matchValue} 使用的比较模式
 */
public record GuiConfigPresetContribution(
        String presetId,
        String labelKey,
        String helpKey,
        String i18nNamespace,
        String cardId,
        int order,
        String matchFieldKey,
        String matchValue,
        Map<String, String> values,
        List<String> lockedFieldKeys,
        GuiConfigPresetMatchMode matchMode
) {

    /**
     * 规范化可选文本、值、锁定字段和匹配模式。
     *
     * @param presetId 预设标识
     * @param labelKey 标签键
     * @param helpKey 帮助文本键
     * @param i18nNamespace 国际化命名空间
     * @param cardId 卡片标识
     * @param order 排序值
     * @param matchFieldKey 匹配字段键
     * @param matchValue 匹配值
     * @param values 值集合
     * @param lockedFieldKeys 锁定字段键集合
     * @param matchMode 匹配模式
     */
    public GuiConfigPresetContribution {
        helpKey = helpKey == null ? "" : helpKey;
        i18nNamespace = blankToNull(i18nNamespace);
        cardId = blankToNull(cardId);
        matchFieldKey = blankToNull(matchFieldKey);
        matchValue = matchValue == null ? "" : matchValue;
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        lockedFieldKeys = lockedFieldKeys == null
                ? List.copyOf(values.keySet())
                : lockedFieldKeys.stream()
                .map(GuiConfigPresetContribution::blankToNull)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        matchMode = matchMode == null ? GuiConfigPresetMatchMode.EQUALS_IGNORE_CASE : matchMode;
    }

    /**
     * 创建不从当前值自动匹配的简单预设。
     *
     * @param presetId section 内稳定的预设 ID
     * @param labelKey 预设标签 i18n key
     * @param order 预设排序提示
     * @param values 选择预设时应用的字段值
     */
    public GuiConfigPresetContribution(String presetId, String labelKey, int order,
                                       Map<String, String> values) {
        this(presetId, labelKey, "", null, null, order, null, "", values, null, null);
    }

    /**
     * 创建带帮助文本和自动匹配、但不属于卡片的预设。
     *
     * @param presetId section 内稳定的预设 ID
     * @param labelKey 预设标签 i18n key
     * @param helpKey 帮助文本 i18n key
     * @param i18nNamespace 可选的 i18n namespace
     * @param order 预设排序提示
     * @param matchFieldKey 用于推断预设的字段 key
     * @param matchValue 匹配值
     * @param values 选择预设时应用的字段值
     */
    public GuiConfigPresetContribution(String presetId, String labelKey, String helpKey,
                                       String i18nNamespace, int order,
                                       String matchFieldKey, String matchValue,
                                       Map<String, String> values) {
        this(presetId, labelKey, helpKey, i18nNamespace, null, order, matchFieldKey, matchValue,
                values, null, null);
    }

    /**
     * 创建属于指定卡片、并锁定全部 value key 的预设。
     *
     * @param presetId section 内稳定的预设 ID
     * @param labelKey 预设标签 i18n key
     * @param helpKey 帮助文本 i18n key
     * @param i18nNamespace 可选的 i18n namespace
     * @param cardId 卡片 ID
     * @param order 预设排序提示
     * @param matchFieldKey 用于推断预设的字段 key
     * @param matchValue 匹配值
     * @param values 选择预设时应用的字段值
     */
    public GuiConfigPresetContribution(String presetId, String labelKey, String helpKey,
                                       String i18nNamespace, String cardId, int order,
                                       String matchFieldKey, String matchValue,
                                       Map<String, String> values) {
        this(presetId, labelKey, helpKey, i18nNamespace, cardId, order, matchFieldKey, matchValue,
                values, null, null);
    }

    /**
     * 创建属于指定卡片且显式声明锁定字段的预设。
     *
     * @param presetId section 内稳定的预设 ID
     * @param labelKey 预设标签 i18n key
     * @param helpKey 帮助文本 i18n key
     * @param i18nNamespace 可选的 i18n namespace
     * @param cardId 卡片 ID
     * @param order 预设排序提示
     * @param matchFieldKey 用于推断预设的字段 key
     * @param matchValue 匹配值
     * @param values 选择预设时应用的字段值
     * @param lockedFieldKeys 选中预设时锁定的字段 key
     */
    public GuiConfigPresetContribution(String presetId, String labelKey, String helpKey,
                                       String i18nNamespace, String cardId, int order,
                                       String matchFieldKey, String matchValue,
                                       Map<String, String> values, List<String> lockedFieldKeys) {
        this(presetId, labelKey, helpKey, i18nNamespace, cardId, order, matchFieldKey, matchValue,
                values, lockedFieldKeys, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
