package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.List;

/**
 * GUI 配置动作的纯数据声明，例如连通性测试或发送测试按钮。
 *
 * @param actionId section 内稳定的动作 ID
 * @param labelKey 按钮标签的 i18n key
 * @param helpKey 可选的帮助文本 i18n key
 * @param i18nNamespace 可选的 i18n namespace；空白表示插件展示 namespace
 * @param cardId 卡片切换布局使用的可选卡片 ID
 * @param endpoint 相对于 {@code /api/gui/} 的 GUI API 端点；贡献插件必须把完全相同的路径发布为
 *                 接受 {@code POST} 的 {@code GUI} 路由，宿主在发送请求时重新核对活动路由 owner
 * @param readTimeoutMillis 该动作的 HTTP 读取超时毫秒数
 * @param order section 内的动作排序提示
 * @param payloadFields 复制到动作请求体中的字段值
 * @param sendingNoticeKey 动作执行期间显示的可选 i18n key
 * @param resultRules 针对有界、结构化且非敏感结果字段的可选响应提示规则；首个匹配规则生效
 * @param resultSummary 供结果规则使用的可选有界结构化响应数组摘要
 */
public record GuiConfigActionContribution(
        String actionId,
        String labelKey,
        String helpKey,
        String i18nNamespace,
        String cardId,
        String endpoint,
        int readTimeoutMillis,
        int order,
        List<GuiConfigActionPayloadField> payloadFields,
        String sendingNoticeKey,
        List<GuiConfigActionResultRule> resultRules,
        GuiConfigActionResultSummary resultSummary
) {

    /**
     * 规范化可选文本和集合。
     *
     * @param actionId 操作标识
     * @param labelKey 标签键
     * @param helpKey 帮助文本键
     * @param i18nNamespace 国际化命名空间
     * @param cardId 卡片标识
     * @param endpoint 端点
     * @param readTimeoutMillis 读取超时毫秒数
     * @param order 排序值
     * @param payloadFields 载荷字段列表
     * @param sendingNoticeKey 发送提示键
     * @param resultRules 结果规则列表
     * @param resultSummary 结果摘要
     */
    public GuiConfigActionContribution {
        helpKey = helpKey == null ? "" : helpKey;
        i18nNamespace = blankToNull(i18nNamespace);
        cardId = blankToNull(cardId);
        endpoint = endpoint == null ? "" : endpoint.trim();
        payloadFields = payloadFields == null ? List.of() : List.copyOf(payloadFields);
        sendingNoticeKey = sendingNoticeKey == null ? "" : sendingNoticeKey;
        resultRules = resultRules == null ? List.of() : List.copyOf(resultRules);
    }

    /**
     * 创建使用默认超时且不声明结果规则的简单动作。
     *
     * @param actionId section 内稳定的动作 ID
     * @param labelKey 按钮标签的 i18n key
     * @param endpoint 相对于 {@code /api/gui/} 的 GUI API 端点
     * @param order section 内的动作排序提示
     * @param payloadFields 复制到动作请求体中的字段值
     */
    public GuiConfigActionContribution(String actionId, String labelKey, String endpoint, int order,
                                       List<GuiConfigActionPayloadField> payloadFields) {
        this(actionId, labelKey, "", null, null, endpoint, 30_000, order, payloadFields,
                "", List.of(), null);
    }

    /**
     * 创建带帮助文本和自定义超时、但不声明结果规则的动作。
     *
     * @param actionId section 内稳定的动作 ID
     * @param labelKey 按钮标签的 i18n key
     * @param helpKey 可选的帮助文本 i18n key
     * @param i18nNamespace 可选的 i18n namespace
     * @param endpoint 相对于 {@code /api/gui/} 的 GUI API 端点
     * @param readTimeoutMillis HTTP 读取超时毫秒数
     * @param order section 内的动作排序提示
     * @param payloadFields 复制到动作请求体中的字段值
     */
    public GuiConfigActionContribution(String actionId, String labelKey, String helpKey, String i18nNamespace,
                                       String endpoint, int readTimeoutMillis, int order,
                                       List<GuiConfigActionPayloadField> payloadFields) {
        this(actionId, labelKey, helpKey, i18nNamespace, null, endpoint, readTimeoutMillis, order,
                payloadFields, "", List.of(), null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
