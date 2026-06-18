package top.sywyar.pixivdownload.plugin.api.web;

import java.util.Set;

/**
 * 插件声明的路由与访问策略。
 *
 * @param pathPattern              路径模式（Ant 风格，如 {@code /api/gallery/**}）
 * @param accessPolicy             访问策略（允许的身份组合，见 {@link AccessPolicy}）
 * @param methods                  允许的 HTTP 方法；空集合表示全部方法
 * @param visibleDuringMaintenance 维护窗口（503）期间是否仍可访问
 */
public record WebRouteContribution(
        String pathPattern,
        AccessPolicy accessPolicy,
        Set<HttpMethod> methods,
        boolean visibleDuringMaintenance
) {
    public WebRouteContribution {
        methods = Set.copyOf(methods);
    }

    /**
     * 路径是否命中本路由的模式。以 {@code **} 结尾的模式按去掉末尾 {@code **} 后的前缀做 {@code startsWith}
     * 匹配（含 {@code /api/authors**} 这类无尾斜杠前缀），否则按精确相等匹配——与 {@code AuthFilter} 的派生口径一致。
     */
    public boolean matches(String path) {
        if (path == null) {
            return false;
        }
        if (pathPattern.endsWith("**")) {
            return path.startsWith(pathPattern.substring(0, pathPattern.length() - 2));
        }
        return path.equals(pathPattern);
    }

    /** 本路由是否接受该 HTTP 方法（空方法集表示接受全部方法）。 */
    public boolean acceptsMethod(HttpMethod method) {
        return methods.isEmpty() || methods.contains(method);
    }
}
