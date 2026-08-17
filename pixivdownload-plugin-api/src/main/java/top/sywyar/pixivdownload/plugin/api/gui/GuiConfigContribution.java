package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.List;

/**
 * GUI 配置分组、字段和丰富 section 元数据的纯数据贡献。
 *
 * @param groups 可选的自定义分组元数据
 * @param fields 插件贡献的字段声明
 * @param sections 布局、动作和预设使用的可选丰富 section 声明
 */
public record GuiConfigContribution(
        List<GuiConfigGroupContribution> groups,
        List<GuiConfigFieldContribution> fields,
        List<GuiConfigSectionContribution> sections
) {

    /**
     * 将缺失集合规范化为空不可变集合。
     *
     * @param groups 分组列表
     * @param fields 字段列表
     * @param sections 区段列表
     */
    public GuiConfigContribution {
        groups = groups == null ? List.of() : List.copyOf(groups);
        fields = fields == null ? List.of() : List.copyOf(fields);
        sections = sections == null ? List.of() : List.copyOf(sections);
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
