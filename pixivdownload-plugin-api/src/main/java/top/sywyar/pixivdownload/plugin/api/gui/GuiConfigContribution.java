package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * GUI 配置分组、字段和丰富 section 元数据的纯数据贡献。
 *
 * @param groups 可选的自定义分组元数据
 * @param fields 插件贡献的字段声明
 * @param sections 布局、动作和预设使用的可选丰富 section 声明
 * @param capabilities 当前活动插件提供的配置能力标识；不是插件 ID，也不代表远程服务已配置或可连接
 * @param optionRequirements 本贡献枚举选项依赖的配置能力与缺席标签
 */
public record GuiConfigContribution(
        List<GuiConfigGroupContribution> groups,
        List<GuiConfigFieldContribution> fields,
        List<GuiConfigSectionContribution> sections,
        Set<String> capabilities,
        List<GuiConfigOptionRequirement> optionRequirements
) {

    /**
     * 将缺失集合规范化为空不可变集合。
     *
     * @param groups 分组列表
     * @param fields 字段列表
     * @param sections 区段列表
     * @param capabilities 配置能力标识集合
     * @param optionRequirements 枚举选项的配置能力依赖
     */
    public GuiConfigContribution {
        groups = groups == null ? List.of() : List.copyOf(groups);
        fields = fields == null ? List.of() : List.copyOf(fields);
        sections = sections == null ? List.of() : List.copyOf(sections);
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        optionRequirements = optionRequirements == null ? List.of() : List.copyOf(optionRequirements);
        if (capabilities.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Configuration capability must not be blank");
        }
        var options = new java.util.HashSet<List<String>>();
        for (var requirement : optionRequirements) {
            if (!options.add(List.of(requirement.fieldKey(), requirement.value()))
                    || fields.stream().noneMatch(field -> field.key().equals(requirement.fieldKey())
                    && field.type() == GuiConfigFieldType.ENUM && field.enumValues().contains(requirement.value()))) {
                throw new IllegalArgumentException("Configuration option requirement must identify a unique local enum option");
            }
        }
    }

    /**
     * 创建不声明跨能力选项提示的配置贡献。
     * @param groups 分组列表
     * @param fields 字段列表
     * @param sections 区段列表
     */
    public GuiConfigContribution(
            List<GuiConfigGroupContribution> groups,
            List<GuiConfigFieldContribution> fields,
            List<GuiConfigSectionContribution> sections
    ) {
        this(groups, fields, sections, Set.of(), List.of());
    }

    /**
     * 按本轮活动配置能力投影枚举标签；只改变展示，不改值、校验或保存规则。
     * 调用方每轮应使用插件的原始贡献，不能以投影结果缓存替代原始声明。
     * @param availableCapabilities 同一活动插件快照中声明的配置能力
     * @return 缺席选项使用本字段 namespace 下提示标签的纯值贡献
     */
    public GuiConfigContribution resolveOptions(Set<String> availableCapabilities) {
        if (optionRequirements.stream().allMatch(item -> availableCapabilities.contains(item.capability()))) return this;
        var resolved = fields.stream().map(field -> {
            var labels = new LinkedHashMap<>(field.enumValueLabelKeys());
            optionRequirements.stream().filter(item -> item.fieldKey().equals(field.key())
                    && !availableCapabilities.contains(item.capability())).forEach(
                    item -> labels.put(item.value(), item.unavailableLabelKey()));
            if (labels.equals(field.enumValueLabelKeys())) return field;
            return new GuiConfigFieldContribution(
                    field.key(),
                    field.groupId(),
                    field.labelKey(),
                    field.helpKey(),
                    field.i18nNamespace(),
                    field.type(),
                    field.defaultValue(),
                    field.order(),
                    field.sensitive(),
                    field.effect(),
                    field.enumValues(),
                    field.enabledWhen(),
                    field.visibleWhen(),
                    field.minValue(),
                    field.maxValue(),
                    field.contributesGroupVisibility(),
                    labels,
                    field.requiredWhen()
            );
        }).toList();
        return new GuiConfigContribution(groups, resolved, sections, capabilities, optionRequirements);
    }

    /**
     * 创建不含丰富 section 的配置贡献。
     *
     * @param groups 自定义分组元数据
     * @param fields 插件贡献的字段声明
     */
    public GuiConfigContribution(List<GuiConfigGroupContribution> groups,
                                 List<GuiConfigFieldContribution> fields) {
        this(groups, fields, List.of());
    }

    /**
     * 创建只包含字段的配置贡献。
     *
     * @param fields 插件贡献的字段声明
     */
    public GuiConfigContribution(List<GuiConfigFieldContribution> fields) {
        this(List.of(), fields, List.of());
    }
}
