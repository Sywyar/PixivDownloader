package top.sywyar.pixivdownload.plugin.api.web;

import java.util.Set;

/**
 * 插件声明的默认启动落点。{@code /redirect} 在 intro 引导态之外，按当前启动上下文解析首选落点；
 * 首选上下文无落点时回退到 {@code order} 最小的已启用插件落点，全部缺失则由核心兜底。
 *
 * @param pluginId          声明方插件 id
 * @param path              落点路径（页面），如 {@code /pixiv-batch.html}
 * @param order             排序权重，越小越靠前（作为首选上下文缺失时的回退优先级）
 * @param preferredContexts 本落点优先匹配的启动上下文；空集合表示只参与回退
 */
public record StartupRouteContribution(
        String pluginId,
        String path,
        int order,
        Set<StartupRouteContext> preferredContexts
) {
    public StartupRouteContribution {
        preferredContexts = preferredContexts == null ? Set.of() : Set.copyOf(preferredContexts);
    }

    public StartupRouteContribution(String pluginId, String path, int order) {
        this(pluginId, path, order, Set.of());
    }
}
