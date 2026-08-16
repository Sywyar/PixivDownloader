package top.sywyar.pixivdownload.plugin.registry;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestOwner;

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
 * 启动构造期只直接聚合内置插件；外置插件由 {@code PluginWebContributionRegistrar} 先发布请求准入 owner，
 * 再携同一 exact generation + serving owner 注册路由，外置路由从不以 owner-null 形态进入快照。
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

    private static final String CORE_PLUGIN_ID = "core";

    /** 一条已注册路由、声明方插件及其可热卸载 serving 的精确请求 owner。 */
    public record RegisteredRoute(
            String pluginId,
            WebRouteContribution route,
            PluginRequestOwner requestOwner) {

        public RegisteredRoute {
            if (pluginId == null || pluginId.isBlank() || route == null) {
                throw new IllegalStateException("invalid registered route owner: " + pluginId);
            }
            if (requestOwner != null && !pluginId.equals(requestOwner.pluginId())) {
                throw new IllegalStateException(
                        "registered route request owner mismatch: " + requestOwner);
            }
        }

        public RegisteredRoute(String pluginId, WebRouteContribution route) {
            this(pluginId, route, null);
        }
    }

    private final Object lock = new Object();

    private volatile List<RegisteredRoute> snapshot = List.of();

    public RouteAccessRegistry(PluginRegistry pluginRegistry) {
        for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.registeredPlugins()) {
            if (registered.source() != PluginSource.BUILT_IN) {
                continue;
            }
            List<WebRouteContribution> routes = registered.plugin().routes();
            if (!routes.isEmpty()) {
                register(registered.id(), routes);
            }
        }
    }

    /**
     * 注册一个插件的全部路由声明。同一 pluginId 重复注册、路径模式非法或
     * 与已注册路由重复或产生访问策略冲突都立即抛出，
     * 使应用启动失败而不是带病运行。
     */
    public void register(String pluginId, List<WebRouteContribution> routes) {
        register(pluginId, null, routes);
    }

    /** 注册外置 serving 的路由；每条路由携带同一个精确 generation + serving owner。 */
    public void register(PluginRequestOwner requestOwner, List<WebRouteContribution> routes) {
        if (requestOwner == null) {
            throw new IllegalStateException("route contribution without plugin request owner");
        }
        register(requestOwner.pluginId(), requestOwner, routes);
    }

    private void register(String pluginId,
                          PluginRequestOwner requestOwner,
                          List<WebRouteContribution> routes) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalStateException("route contribution without pluginId");
        }
        if (requestOwner != null && !pluginId.equals(requestOwner.pluginId())) {
            throw new IllegalStateException("route contribution request owner mismatch: " + requestOwner);
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
                rejectPolicyConflict(next, pluginId, route);
                if (!keys.add(routeKey(route))) {
                    throw new IllegalStateException("duplicate route contribution: "
                            + route.pathPattern() + " (plugin: " + pluginId + ")");
                }
                next.add(new RegisteredRoute(pluginId, route, requestOwner));
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

    /**
     * 只撤回精确外置 serving 的路由；旧 generation / serving 的迟到清理不会按 pluginId 删除后来发布的新代。
     * 未发布过或已经撤回时静默完成，供可重试清理使用。
     */
    public void unregister(PluginRequestOwner requestOwner) {
        if (requestOwner == null) {
            return;
        }
        synchronized (lock) {
            snapshot = snapshot.stream()
                    .filter(registered -> !requestOwner.equals(registered.requestOwner()))
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

    /** 是否由指定插件声明了同时命中路径和方法的路由。供插件自有 controller 的注册边界校验。 */
    public boolean isDeclaredBy(String pluginId, String path, HttpMethod method) {
        if (pluginId == null || pluginId.isBlank()) {
            return false;
        }
        for (RegisteredRoute registered : snapshot) {
            if (registered.pluginId().equals(pluginId)
                    && registered.route().matches(path)
                    && registered.route().acceptsMethod(method)) {
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
     * ③ 显式方法集（命中请求方法）优先于空方法集（= 全部方法）。同等特异性候选若策略不同则属声明歧义，
     * 立即抛出（fail-fast，不静默依赖注册顺序）。
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

    /** 同等特异性多候选策略全相同取其一；否则属声明歧义、fail-fast。 */
    private static RegisteredRoute disambiguate(List<RegisteredRoute> candidates, String path, HttpMethod method) {
        AccessPolicy first = candidates.get(0).route().accessPolicy();
        for (RegisteredRoute registered : candidates) {
            if (registered.route().accessPolicy() != first) {
                throw new IllegalStateException("ambiguous route resolution for " + method + " " + path + ": "
                        + candidates.stream()
                                .map(candidate -> candidate.route().pathPattern() + "="
                                        + candidate.route().accessPolicy())
                                .collect(Collectors.joining(", ")));
            }
        }
        return candidates.get(0);
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
        if (route.accessPolicy() == AccessPolicy.ACTUATOR_PUBLIC
                && !CORE_PLUGIN_ID.equals(pluginId)) {
            throw new IllegalStateException("ACTUATOR_PUBLIC route is reserved for the core host"
                    + " (plugin: " + pluginId + ")");
        }
    }

    private static void rejectPolicyConflict(List<RegisteredRoute> registeredRoutes,
                                             String pluginId,
                                             WebRouteContribution proposed) {
        for (RegisteredRoute registered : registeredRoutes) {
            WebRouteContribution existing = registered.route();
            if (!methodsOverlap(existing, proposed) || existing.accessPolicy() == proposed.accessPolicy()) {
                continue;
            }
            boolean samePattern = existing.pathPattern().equals(proposed.pathPattern());
            boolean crossPluginOverlap = !registered.pluginId().equals(pluginId)
                    && pathPatternsOverlap(existing.pathPattern(), proposed.pathPattern());
            if (samePattern || crossPluginOverlap) {
                throw new IllegalStateException("conflicting route access policy: "
                        + "plugin '" + pluginId + "' " + proposed.pathPattern() + "=" + proposed.accessPolicy()
                        + " overlaps plugin '" + registered.pluginId() + "' "
                        + existing.pathPattern() + "=" + existing.accessPolicy());
            }
        }
    }

    private static boolean methodsOverlap(WebRouteContribution left, WebRouteContribution right) {
        return left.methods().isEmpty() || right.methods().isEmpty()
                || left.methods().stream().anyMatch(right.methods()::contains);
    }

    private static boolean pathPatternsOverlap(String left, String right) {
        boolean leftPrefix = left.endsWith("**");
        boolean rightPrefix = right.endsWith("**");
        if (leftPrefix && rightPrefix) {
            String leftValue = left.substring(0, left.length() - 2);
            String rightValue = right.substring(0, right.length() - 2);
            return leftValue.startsWith(rightValue) || rightValue.startsWith(leftValue);
        }
        if (leftPrefix) {
            return canMatchPrefix(right, left.substring(0, left.length() - 2));
        }
        if (rightPrefix) {
            return canMatchPrefix(left, right.substring(0, right.length() - 2));
        }
        if (!left.contains("*") && !right.contains("*")) {
            return left.equals(right);
        }
        String[] leftSegments = left.split("/");
        String[] rightSegments = right.split("/");
        if (leftSegments.length != rightSegments.length) {
            return false;
        }
        for (int i = 0; i < leftSegments.length; i++) {
            if (!leftSegments[i].equals(rightSegments[i])
                    && !leftSegments[i].equals("*")
                    && !rightSegments[i].equals("*")) {
                return false;
            }
        }
        return true;
    }

    private static boolean canMatchPrefix(String pattern, String prefix) {
        return canMatchPrefix(pattern, 0, prefix, 0,
                new Boolean[pattern.length() + 1][prefix.length() + 1]);
    }

    private static boolean canMatchPrefix(
            String pattern, int patternIndex, String prefix, int prefixIndex, Boolean[][] memo) {
        if (prefixIndex == prefix.length()) {
            return true;
        }
        if (patternIndex == pattern.length()) {
            return false;
        }
        Boolean cached = memo[patternIndex][prefixIndex];
        if (cached != null) {
            return cached;
        }
        boolean wildcardSegment = pattern.charAt(patternIndex) == '*'
                && (patternIndex == 0 || pattern.charAt(patternIndex - 1) == '/')
                && (patternIndex + 1 == pattern.length() || pattern.charAt(patternIndex + 1) == '/');
        boolean result;
        if (wildcardSegment) {
            result = canMatchPrefix(pattern, patternIndex + 1, prefix, prefixIndex, memo)
                    || (prefix.charAt(prefixIndex) != '/'
                    && canMatchPrefix(pattern, patternIndex, prefix, prefixIndex + 1, memo));
        } else {
            result = pattern.charAt(patternIndex) == prefix.charAt(prefixIndex)
                    && canMatchPrefix(pattern, patternIndex + 1, prefix, prefixIndex + 1, memo);
        }
        memo[patternIndex][prefixIndex] = result;
        return result;
    }

    /** 同一（模式, 方法集, 访问策略）三元组视为重复声明；方法集排序后参与键值。 */
    private static String routeKey(WebRouteContribution route) {
        return route.pathPattern() + "|" + route.accessPolicy() + "|" + new TreeSet<>(route.methods());
    }
}
