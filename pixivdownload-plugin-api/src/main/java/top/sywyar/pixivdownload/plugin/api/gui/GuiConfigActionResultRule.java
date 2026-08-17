package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.List;

/**
 * GUI 配置动作响应的声明式提示规则。
 *
 * @param noticeKey 全部条件匹配时显示的提示 i18n key
 * @param i18nNamespace 可选的 i18n namespace；空白表示插件展示 namespace
 * @param order 规则顺序；首个匹配规则生效
 * @param conditions 必须全部匹配的条件
 * @param arguments 从响应读取的提示参数
 */
public record GuiConfigActionResultRule(
        String noticeKey,
        String i18nNamespace,
        int order,
        List<GuiConfigActionResultCondition> conditions,
        List<GuiConfigActionResultArgument> arguments
) {

    /**
     * 规范化 i18n namespace、条件和参数。
     *
     * @param noticeKey 通知键
     * @param i18nNamespace 国际化命名空间
     * @param order 排序值
     * @param conditions 条件列表
     * @param arguments 参数列表
     */
    public GuiConfigActionResultRule {
        i18nNamespace = blankToNull(i18nNamespace);
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    /**
     * 使用插件展示 namespace 创建提示规则。
     *
     * @param noticeKey 提示 i18n key
     * @param order 规则顺序
     * @param conditions 必须全部匹配的条件
     * @param arguments 从响应读取的提示参数
     */
    public GuiConfigActionResultRule(String noticeKey, int order,
                                     List<GuiConfigActionResultCondition> conditions,
                                     List<GuiConfigActionResultArgument> arguments) {
        this(noticeKey, null, order, conditions, arguments);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
