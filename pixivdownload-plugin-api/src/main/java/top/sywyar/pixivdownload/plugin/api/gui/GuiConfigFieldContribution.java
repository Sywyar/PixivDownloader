package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个 GUI 配置字段的纯数据声明。
 *
 * @param key owner 作用域插件配置 key；宿主分别把普通值和敏感值存入插件配置与凭据存储
 * @param groupId 稳定分组 ID；内置 ID 见 {@link GuiConfigGroups}
 * @param labelKey 字段标签的 i18n key
 * @param helpKey 可选的帮助文本 i18n key
 * @param i18nNamespace 可选的 i18n namespace；空白表示插件展示 namespace
 * @param type 控件类型
 * @param defaultValue 配置 key 缺失时使用的值
 * @param order 插件贡献字段内的排序提示
 * @param sensitive 值是否为 secret；{@link GuiConfigFieldType#PASSWORD} 始终规范化为敏感字段
 * @param effect 保存变更值后的生效方式
 * @param enumValues {@link GuiConfigFieldType#ENUM} 允许的值
 * @param enabledWhen 字段启用前必须全部匹配的条件
 * @param visibleWhen 字段可见前必须全部匹配的条件
 * @param minValue 类 INT 值的可选最小值
 * @param maxValue 类 INT 值的可选最大值
 * @param contributesGroupVisibility 是否仅凭该字段就让所属分组显示为标签页
 * @param enumValueLabelKeys 可选的枚举值到 i18n 标签 key 映射
 */
public record GuiConfigFieldContribution(
        String key,
        String groupId,
        String labelKey,
        String helpKey,
        String i18nNamespace,
        GuiConfigFieldType type,
        String defaultValue,
        int order,
        boolean sensitive,
        GuiConfigEffect effect,
        List<String> enumValues,
        List<GuiConfigCondition> enabledWhen,
        List<GuiConfigCondition> visibleWhen,
        Integer minValue,
        Integer maxValue,
        boolean contributesGroupVisibility,
        Map<String, String> enumValueLabelKeys
) {

    /**
     * 规范化可选文本、敏感属性、集合和映射。
     *
     * @param key 键
     * @param groupId 分组标识
     * @param labelKey 标签键
     * @param helpKey 帮助文本键
     * @param i18nNamespace 国际化命名空间
     * @param type 类型
     * @param defaultValue 默认值
     * @param order 排序值
     * @param sensitive 是否包含敏感信息
     * @param effect 生效方式
     * @param enumValues 枚举值列表
     * @param enabledWhen 启用条件
     * @param visibleWhen 可见条件
     * @param minValue 最小值
     * @param maxValue 最大值
     * @param contributesGroupVisibility 贡献状态分组可见性
     * @param enumValueLabelKeys 枚举值标签键映射
     */
    public GuiConfigFieldContribution {
        helpKey = helpKey == null ? "" : helpKey;
        i18nNamespace = blankToNull(i18nNamespace);
        defaultValue = defaultValue == null ? "" : defaultValue;
        sensitive = sensitive || type == GuiConfigFieldType.PASSWORD;
        effect = effect == null ? GuiConfigEffect.BACKEND_RESTART : effect;
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        enabledWhen = enabledWhen == null ? List.of() : List.copyOf(enabledWhen);
        visibleWhen = visibleWhen == null ? List.of() : List.copyOf(visibleWhen);
        enumValueLabelKeys = enumValueLabelKeys == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(enumValueLabelKeys));
    }

    /**
     * 创建采用默认重启和分组可见性语义的基础字段。
     *
     * @param key owner 作用域配置 key
     * @param groupId 稳定分组 ID
     * @param labelKey 字段标签 i18n key
     * @param type 控件类型
     * @param defaultValue 默认值
     * @param order 排序提示
     */
    public GuiConfigFieldContribution(String key, String groupId, String labelKey,
                                      GuiConfigFieldType type, String defaultValue, int order) {
        this(key, groupId, labelKey, "", null, type, defaultValue, order,
                false, GuiConfigEffect.BACKEND_RESTART, List.of(), List.of(), List.of(),
                null, null, true, Map.of());
    }

    /**
     * 创建带帮助文本、敏感和重启语义的字段。
     *
     * @param key owner 作用域配置 key
     * @param groupId 稳定分组 ID
     * @param labelKey 字段标签 i18n key
     * @param helpKey 帮助文本 i18n key
     * @param type 控件类型
     * @param defaultValue 默认值
     * @param order 排序提示
     * @param sensitive 值是否为 secret
     * @param effect 保存变更值后的生效方式
     */
    public GuiConfigFieldContribution(String key, String groupId, String labelKey, String helpKey,
                                      GuiConfigFieldType type, String defaultValue, int order,
                                      boolean sensitive, GuiConfigEffect effect) {
        this(key, groupId, labelKey, helpKey, null, type, defaultValue, order,
                sensitive, effect, List.of(), List.of(), List.of(), null, null, true, Map.of());
    }

    /**
     * 创建带约束和条件、并默认贡献分组可见性的字段。
     *
     * @param key owner 作用域配置 key
     * @param groupId 稳定分组 ID
     * @param labelKey 字段标签 i18n key
     * @param helpKey 帮助文本 i18n key
     * @param i18nNamespace 可选的 i18n namespace
     * @param type 控件类型
     * @param defaultValue 默认值
     * @param order 排序提示
     * @param sensitive 值是否为 secret
     * @param effect 保存变更值后的生效方式
     * @param enumValues 枚举允许值
     * @param enabledWhen 启用条件
     * @param visibleWhen 可见条件
     * @param minValue 可选最小值
     * @param maxValue 可选最大值
     */
    public GuiConfigFieldContribution(
            String key,
            String groupId,
            String labelKey,
            String helpKey,
            String i18nNamespace,
            GuiConfigFieldType type,
            String defaultValue,
            int order,
            boolean sensitive,
            GuiConfigEffect effect,
            List<String> enumValues,
            List<GuiConfigCondition> enabledWhen,
            List<GuiConfigCondition> visibleWhen,
            Integer minValue,
            Integer maxValue
    ) {
        this(key, groupId, labelKey, helpKey, i18nNamespace, type, defaultValue, order,
                sensitive, effect, enumValues, enabledWhen, visibleWhen, minValue, maxValue, true, Map.of());
    }

    /**
     * 创建带约束、条件和显式分组可见性语义的字段。
     *
     * @param key owner 作用域配置 key
     * @param groupId 稳定分组 ID
     * @param labelKey 字段标签 i18n key
     * @param helpKey 帮助文本 i18n key
     * @param i18nNamespace 可选的 i18n namespace
     * @param type 控件类型
     * @param defaultValue 默认值
     * @param order 排序提示
     * @param sensitive 值是否为 secret
     * @param effect 保存变更值后的生效方式
     * @param enumValues 枚举允许值
     * @param enabledWhen 启用条件
     * @param visibleWhen 可见条件
     * @param minValue 可选最小值
     * @param maxValue 可选最大值
     * @param contributesGroupVisibility 是否让所属分组显示为标签页
     */
    public GuiConfigFieldContribution(
            String key,
            String groupId,
            String labelKey,
            String helpKey,
            String i18nNamespace,
            GuiConfigFieldType type,
            String defaultValue,
            int order,
            boolean sensitive,
            GuiConfigEffect effect,
            List<String> enumValues,
            List<GuiConfigCondition> enabledWhen,
            List<GuiConfigCondition> visibleWhen,
            Integer minValue,
            Integer maxValue,
            boolean contributesGroupVisibility
    ) {
        this(key, groupId, labelKey, helpKey, i18nNamespace, type, defaultValue, order,
                sensitive, effect, enumValues, enabledWhen, visibleWhen, minValue, maxValue,
                contributesGroupVisibility, Map.of());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
