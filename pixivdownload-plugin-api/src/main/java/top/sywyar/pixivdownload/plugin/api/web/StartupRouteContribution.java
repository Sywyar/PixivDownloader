package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 插件声明的默认启动落点。{@code /redirect} 在 intro 引导态之外，按当前模式选定的首选插件
 * 解析其落点；首选插件未启用时回退到 {@code order} 最小的已启用插件落点，全部缺失则由核心兜底。
 *
 * @param pluginId 声明方插件 id
 * @param path     落点路径（页面），如 {@code /pixiv-batch.html}
 * @param order    排序权重，越小越靠前（作为首选插件缺失时的回退优先级）
 */
public record StartupRouteContribution(
        String pluginId,
        String path,
        int order
) {
}
