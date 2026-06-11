package top.sywyar.pixivdownload.plugin.api;

import java.util.Set;

/**
 * 插件声明的路由与访问级别。
 *
 * @param pathPattern              路径模式（Ant 风格，如 {@code /api/gallery/**}）
 * @param accessLevel              访问级别
 * @param methods                  允许的 HTTP 方法；空集合表示全部方法
 * @param visibleDuringMaintenance 维护窗口（503）期间是否仍可访问
 */
public record WebRouteContribution(
        String pathPattern,
        AccessLevel accessLevel,
        Set<HttpMethod> methods,
        boolean visibleDuringMaintenance
) {
    public WebRouteContribution {
        methods = Set.copyOf(methods);
    }
}
