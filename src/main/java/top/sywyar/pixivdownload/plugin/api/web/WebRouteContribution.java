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

    // ── 命名静态工厂（对标 List.of / Optional.of）：声明「指定访问策略 + 全部 HTTP 方法 + 维护窗口不可见」
    //    这一常见档，各对应一个 AccessPolicy，全插件统一复用。需要限定 HTTP 方法集或维护期可见的少数特例，
    //    继续用标准构造器 new WebRouteContribution(pattern, policy, methods, visibleDuringMaintenance) 兜底。

    /** 公开路由（{@link AccessPolicy#PUBLIC}）：任何人无需鉴权即可访问，solo / multi 一致。 */
    public static WebRouteContribution publicRoute(String pathPattern) {
        return new WebRouteContribution(pathPattern, AccessPolicy.PUBLIC, Set.of(), false);
    }

    /**
     * 「登录用户或 multi 访客」路由（{@link AccessPolicy#VISITOR}）：multi 访客可达、solo 需会话、
     * 受邀访客 403、不入 monitor。
     */
    public static WebRouteContribution visitor(String pathPattern) {
        return new WebRouteContribution(pathPattern, AccessPolicy.VISITOR, Set.of(), false);
    }

    /**
     * 访客与受邀访客均可只读、不受 monitor 管控（{@link AccessPolicy#VISITOR_AND_INVITED_GUEST}）：
     * 跨页共享只读静态依赖与只读代理 / 状态轮询端点。
     */
    public static WebRouteContribution visitorAndInvitedGuest(String pathPattern) {
        return new WebRouteContribution(pathPattern, AccessPolicy.VISITOR_AND_INVITED_GUEST, Set.of(), false);
    }

    /**
     * 受邀访客可读 + 登录用户 / 管理员、同时受 monitor 管控（{@link AccessPolicy#INVITED_GUEST}）：
     * 画廊 / 小说页面与其 API。
     */
    public static WebRouteContribution invitedGuest(String pathPattern) {
        return new WebRouteContribution(pathPattern, AccessPolicy.INVITED_GUEST, Set.of(), false);
    }

    /** 管理员专属（{@link AccessPolicy#ADMIN}）：受 monitor 管控、绝不入访客 / 公开清单。 */
    public static WebRouteContribution admin(String pathPattern) {
        return new WebRouteContribution(pathPattern, AccessPolicy.ADMIN, Set.of(), false);
    }

    /** 本机放行特例（{@link AccessPolicy#LOCAL}）：本地回环直通、远端回退常规鉴权。 */
    public static WebRouteContribution local(String pathPattern) {
        return new WebRouteContribution(pathPattern, AccessPolicy.LOCAL, Set.of(), false);
    }

    /** {@code /api/gui/**} 双重校验（{@link AccessPolicy#GUI}）：本机可信请求 + 有效 GUI token，由 AuthFilter 内联分支执行。 */
    public static WebRouteContribution gui(String pathPattern) {
        return new WebRouteContribution(pathPattern, AccessPolicy.GUI, Set.of(), false);
    }

    /** actuator 公开探针（{@link AccessPolicy#ACTUATOR_PUBLIC}）：health / info，由 AuthFilter 内联 fast-path 放行。 */
    public static WebRouteContribution actuatorPublic(String pathPattern) {
        return new WebRouteContribution(pathPattern, AccessPolicy.ACTUATOR_PUBLIC, Set.of(), false);
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
