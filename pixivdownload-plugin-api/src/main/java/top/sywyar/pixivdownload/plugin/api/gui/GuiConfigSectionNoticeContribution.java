package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * 在 GUI 配置 section 顶部渲染的简短提示纯数据声明。
 *
 * @param noticeId 用于合并多个插件重复提示的稳定 ID
 * @param textKey 提示文本的 i18n key
 * @param i18nNamespace 可选的 i18n namespace；空白表示 section 所属插件的展示 namespace
 * @param style 宿主中立的视觉样式
 * @param order section 内的排序提示
 */
public record GuiConfigSectionNoticeContribution(
        String noticeId,
        String textKey,
        String i18nNamespace,
        GuiConfigSectionNoticeStyle style,
        int order
) {

    /**
     * 规范化提示 ID、文本、namespace 和样式。
     *
     * @param noticeId 通知标识
     * @param textKey 文本键
     * @param i18nNamespace 国际化命名空间
     * @param style 样式
     * @param order 排序值
     */
    public GuiConfigSectionNoticeContribution {
        noticeId = noticeId == null ? "" : noticeId.trim();
        textKey = textKey == null ? "" : textKey;
        i18nNamespace = blankToNull(i18nNamespace);
        style = style == null ? GuiConfigSectionNoticeStyle.HINT : style;
    }

    /**
     * 创建使用插件展示 namespace 和提示样式的 section 提示。
     *
     * @param noticeId 稳定提示 ID
     * @param textKey 提示文本 i18n key
     * @param order 排序提示
     */
    public GuiConfigSectionNoticeContribution(String noticeId, String textKey, int order) {
        this(noticeId, textKey, null, GuiConfigSectionNoticeStyle.HINT, order);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
