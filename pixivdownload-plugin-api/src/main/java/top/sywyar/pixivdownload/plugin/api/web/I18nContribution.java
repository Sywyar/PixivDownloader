package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 插件声明的 i18n namespace。bundle 解析必须经声明方插件的 ClassLoader。
 *
 * @param namespace 前端 {@code PixivI18n.create} 使用的 namespace 名
 * @param baseName  ResourceBundle baseName，如 {@code i18n.web.gallery}
 * @param order     {@code /api/i18n/meta} 合并各插件 namespace 后的展示顺序，升序；
 *                  同 order 保持注册顺序。未声明（二参构造器）时取 {@link Integer#MAX_VALUE}，
 *                  自然追加在所有声明了顺序的 namespace 之后。
 */
public record I18nContribution(
        String namespace,
        String baseName,
        int order
) {

    /**
     * 不声明展示顺序：order 取 {@link Integer#MAX_VALUE}，
     * 在 {@code /api/i18n/meta} 中追加到所有声明了顺序的 namespace 之后。
     */
    public I18nContribution(String namespace, String baseName) {
        this(namespace, baseName, Integer.MAX_VALUE);
    }
}
