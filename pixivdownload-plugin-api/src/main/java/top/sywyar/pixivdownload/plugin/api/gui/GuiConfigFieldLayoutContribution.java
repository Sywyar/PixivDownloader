package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * GUI 配置贡献字段的布局元数据。
 *
 * @param fieldKey 要放置的配置字段 key；宿主只接受贡献插件拥有的字段
 * @param cardId 卡片切换布局使用的可选卡片 ID
 * @param cardLabelKey 卡片标签的可选 i18n key
 * @param i18nNamespace 可选的 i18n namespace；空白表示插件展示 namespace
 * @param order 字段在布局容器内的排序提示
 */
public record GuiConfigFieldLayoutContribution(
        String fieldKey,
        String cardId,
        String cardLabelKey,
        String i18nNamespace,
        int order
) {

    /**
     * 规范化卡片和 i18n 元数据。
     *
     * @param fieldKey 字段键
     * @param cardId 卡片标识
     * @param cardLabelKey 卡片标签键
     * @param i18nNamespace 国际化命名空间
     * @param order 排序值
     */
    public GuiConfigFieldLayoutContribution {
        cardId = blankToNull(cardId);
        cardLabelKey = cardLabelKey == null ? "" : cardLabelKey;
        i18nNamespace = blankToNull(i18nNamespace);
    }

    /**
     * 创建不属于卡片的字段布局。
     *
     * @param fieldKey 配置字段 key
     * @param order 排序提示
     */
    public GuiConfigFieldLayoutContribution(String fieldKey, int order) {
        this(fieldKey, null, "", null, order);
    }

    /**
     * 创建使用插件展示 namespace 的卡片字段布局。
     *
     * @param fieldKey 配置字段 key
     * @param cardId 卡片 ID
     * @param cardLabelKey 卡片标签 i18n key
     * @param order 排序提示
     */
    public GuiConfigFieldLayoutContribution(String fieldKey, String cardId, String cardLabelKey, int order) {
        this(fieldKey, cardId, cardLabelKey, null, order);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
