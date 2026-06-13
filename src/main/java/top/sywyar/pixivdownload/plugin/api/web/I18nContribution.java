package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 插件声明的 i18n namespace。bundle 解析必须经声明方插件的 ClassLoader。
 *
 * @param namespace 前端 {@code PixivI18n.create} 使用的 namespace 名
 * @param baseName  ResourceBundle baseName，如 {@code i18n.web.gallery}
 */
public record I18nContribution(
        String namespace,
        String baseName
) {
}
