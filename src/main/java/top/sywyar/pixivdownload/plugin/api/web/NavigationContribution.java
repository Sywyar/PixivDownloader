package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 插件声明的导航项。{@code /api/navigation} 按当前用户可见性过滤后返回。
 *
 * @param id           导航项唯一 id
 * @param labelI18nKey 标签的 i18n key（不直接携带文案）
 * @param href         目标链接
 * @param icon         图标标识
 * @param visibleTo    可见所需的访问策略（与 {@code /api/navigation} 的可见性过滤对照）
 * @param order        排序权重，越小越靠前
 */
public record NavigationContribution(
        String id,
        String labelI18nKey,
        String href,
        String icon,
        AccessPolicy visibleTo,
        int order
) {
}
