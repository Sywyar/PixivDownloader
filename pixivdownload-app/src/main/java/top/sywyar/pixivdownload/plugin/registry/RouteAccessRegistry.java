package top.sywyar.pixivdownload.plugin.registry;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 路由访问注册中心。收集各插件的 {@link WebRouteContribution}，
 * 按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁。
 * <p>
 * {@code AuthFilter} 在每个请求上读取本注册中心的不可变快照：monitor 受保护与「未声明即 404」判定经
 * {@link #resolve}/{@link #isDeclared(String, HttpMethod)} 按「path + HTTP 方法命中的<b>最具体</b>声明」
 * 解析有效访问策略（窄声明覆盖宽前缀，宽前缀不吞窄端点），访客白名单 / 公开 / 本地放行清单仍由 AuthFilter
 * 按访问策略派生。因此插件注册 / 注销替换快照后，过滤判定会随新快照更新。
 * {@code RouteAccessMirrorTest} 守护「访问策略 → 安全分类不变量」，全 URL 声明守卫
 * （{@code RouteDeclarationCoverageTest}）守护「每个真实 controller 方法 / 静态 URL 都已声明」，
 * 金标准 {@code AuthFilterTest} 守护过滤行为本身。
 */
@Component
public class RouteAccessRegistry {

    /** 一条已注册路由及其声明方插件。 */
    public record RegisteredRoute(String pluginId, WebRouteContribution route) {
    }

    private final Object lock = new Object();

    private volatile List<RegisteredRoute> snapshot = List.of();

    public RouteAccessRegistry(PluginRegistry pluginRegistry) {
        for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.registeredPlugins()) {
            PixivFeaturePlugin plugin = registered.plugin();
            List<WebRouteContribution> routes = plugin.routes();
            if (!routes.isEmpty()) {
                register(registered.id(), routes);
            }
        }
    }

    /**
     * 注册一个插件的全部路由声明。同一 pluginId 重复注册、路径模式非法或
     * 与已注册路由的（模式, 方法集, 访问级别）三元组完全重复都立即抛出，
     * 使应用启动失败而不是带病运行。
     */
    public void register(String pluginId, List<WebRouteContribution> routes) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalStateException("route contribution without pluginId");
        }
        if (routes == null || routes.isEmpty()) {
            throw new IllegalStateException("empty route contribution (plugin: " + pluginId + ")");
        }
        synchronized (lock) {
            if (snapshot.stream().anyMatch(registered -> registered.pluginId().equals(pluginId))) {
                throw new IllegalStateException("routes already registered for plugin: " + pluginId);
            }
            Set<String> keys = snapshot.stream()
                    .map(registered -> routeKey(registered.route()))
                    .collect(Collectors.toCollection(HashSet::new));
            List<RegisteredRoute> next = new ArrayList<>(snapshot);
            for (WebRouteContribution route : routes) {
                validate(route, pluginId);
                if (!keys.add(routeKey(route))) {
                    throw new IllegalStateException("duplicate route contribution: "
                            + route.pathPattern() + " (plugin: " + pluginId + ")");
                }
                next.add(new RegisteredRoute(pluginId, route));
            }
            snapshot = List.copyOf(next);
        }
    }

    /**
     * 注销一个插件的全部路由。插件可以不声明任何路由，统一卸载流程会对每个插件调用，
     * 因此对未注册过的 pluginId 静默返回。
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            snapshot = snapshot.stream()
                    .filter(registered -> !registered.pluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        }
    }

    /** 按注册顺序返回全部路由的不可变快照。 */
    public List<RegisteredRoute> routes() {
        return snapshot;
    }

    /**
     * 是否有任一已注册路由的模式命中该路径（<b>忽略 HTTP 方法</b>的 path-level 助手）。供仅按路径判定的
     * 全 URL 声明守卫（静态资源目录 / 顶层 HTML 页面，本就只服务 GET/HEAD）使用；需区分方法时用
     * {@link #isDeclared(String, HttpMethod)}。
     */
    public boolean isDeclared(String path) {
        for (RegisteredRoute registered : snapshot) {
            if (registered.route().matches(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否有任一已注册路由<b>同时</b>命中该路径且接受该方法。供 method-aware 的全 URL 声明守卫
     * （{@code RouteDeclarationCoverageTest} 逐 controller 方法）与 {@code AuthFilter} 的「未声明即 404」
     * 请求侧判定使用：仅声明了某方法的 URL 用别的方法访问视为未声明（除非另有更宽的全方法声明覆盖）。
     * 仅判存在性、不解析有效策略，故不抛歧义。
     */
    public boolean isDeclared(String path, HttpMethod method) {
        for (RegisteredRoute registered : snapshot) {
            if (registered.route().matches(path) && registered.route().acceptsMethod(method)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析当前请求（path + HTTP 方法）命中的<b>有效</b>路由：在全部「路径匹配且接受该方法」的已注册路由中，
     * 按特异性择一。供 {@code AuthFilter} 把「最具体声明 + 方法」解析为有效访问策略——更具体的窄声明覆盖更宽的
     * 前缀声明，使宽前缀不会吞掉其下更窄的端点（如宽 {@code /api/tts/**}=ADMIN 不再吞掉窄
     * {@code POST /api/tts/edge/synthesize}）。
     * <p>特异性从高到低：① 精确模式优先于前缀模式；② 前缀更长（去 {@code **} 后更长）优先于更短；
     * ③ 显式方法集（命中请求方法）优先于空方法集（= 全部方法）。同等特异性下：候选含 {@link AccessPolicy#PUBLIC}
     * 时返回 PUBLIC（无条件公开、可达面是全集，与历史「先判 isPublic」一致，叠加更窄策略不收紧其公开性）；
     * 若同等特异性候选策略互不相同且都非 PUBLIC，则属声明歧义、立即抛出（fail-fast，不静默依赖注册顺序）。
     * 无匹配返回空（{@code AuthFilter} 据此统一 404）。
     */
    public Optional<RegisteredRoute> resolve(String path, HttpMethod method) {
        List<RegisteredRoute> snap = snapshot;
        List<RegisteredRoute> best = new ArrayList<>();
        long bestScore = Long.MIN_VALUE;
        for (RegisteredRoute registered : snap) {
            WebRouteContribution route = registered.route();
            if (!route.matches(path) || !route.acceptsMethod(method)) {
                continue;
            }
            long score = specificity(route);
            if (score > bestScore) {
                bestScore = score;
                best.clear();
                best.add(registered);
            } else if (score == bestScore) {
                best.add(registered);
            }
        }
        if (best.isEmpty()) {
            return Optional.empty();
        }
        if (best.size() == 1) {
            return Optional.of(best.get(0));
        }
        return Optional.of(disambiguate(best, path, method));
    }

    /** 同等特异性多候选的择一：PUBLIC（无条件公开）胜出；策略全相同取其一；否则属声明歧义、fail-fast。 */
    private static RegisteredRoute disambiguate(List<RegisteredRoute> candidates, String path, HttpMethod method) {
        AccessPolicy first = candidates.get(0).route().accessPolicy();
        boolean conflicting = false;
        for (RegisteredRoute registered : candidates) {
            AccessPolicy policy = registered.route().accessPolicy();
            if (policy == AccessPolicy.PUBLIC) {
                return registered;
            }
            if (policy != first) {
                conflicting = true;
            }
        }
        if (!conflicting) {
            return candidates.get(0);
        }
        throw new IllegalStateException("ambiguous route resolution for " + method + " " + path + ": "
                + candidates.stream()
                        .map(registered -> registered.route().pathPattern() + "=" + registered.route().accessPolicy())
                        .collect(Collectors.joining(", ")));
    }

    /**
     * 特异性评分（越大越具体）：精确模式整体高于任何前缀（用远大于任何路径长度的基数抬升）；同类按匹配长度
     * （前缀去 {@code **} 后）；同模式下显式方法集再 +1，使「同模式、显式方法」优先于「同模式、空方法集」。
     */
    private static long specificity(WebRouteContribution route) {
        String pattern = route.pathPattern();
        boolean prefix = pattern.endsWith("**");
        int length = prefix ? pattern.length() - 2 : pattern.length();
        long exactBase = prefix ? 0L : 1_000_000L;
        long methodBonus = route.methods().isEmpty() ? 0L : 1L;
        return exactBase + (long) length * 2L + methodBonus;
    }

    private static void validate(WebRouteContribution route, String pluginId) {
        if (route == null) {
            throw new IllegalStateException("null route contribution (plugin: " + pluginId + ")");
        }
        String pattern = route.pathPattern();
        if (pattern == null || pattern.isBlank() || !pattern.startsWith("/")) {
            throw new IllegalStateException("invalid route path pattern: " + pattern
                    + " (plugin: " + pluginId + ")");
        }
        if (route.accessPolicy() == null) {
            throw new IllegalStateException("route without access policy: " + pattern
                    + " (plugin: " + pluginId + ")");
        }
    }

    /** 同一（模式, 方法集, 访问策略）三元组视为重复声明；方法集排序后参与键值。 */
    private static String routeKey(WebRouteContribution route) {
        return route.pathPattern() + "|" + route.accessPolicy() + "|" + new TreeSet<>(route.methods());
    }
}
