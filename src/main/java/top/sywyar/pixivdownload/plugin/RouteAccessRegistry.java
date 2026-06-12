package top.sywyar.pixivdownload.plugin;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.WebRouteContribution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 路由访问注册中心。收集各插件的 {@link WebRouteContribution}，
 * 按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁
 * （{@code AuthFilter} 切换后会在每个请求上读取）。
 * <p>
 * 当前为镜像模式：registry 输出与 {@code AuthFilter} 现有硬编码清单由镜像一致性测试
 * 逐条对照，{@code AuthFilter} 本身尚不读取本注册中心——安全边界的切换在镜像测试
 * 长期保持绿色之后单独进行。
 */
@Component
public class RouteAccessRegistry {

    /** 一条已注册路由及其声明方插件。 */
    public record RegisteredRoute(String pluginId, WebRouteContribution route) {
    }

    private final Object lock = new Object();

    private volatile List<RegisteredRoute> snapshot = List.of();

    public RouteAccessRegistry(PluginRegistry pluginRegistry) {
        for (PixivFeaturePlugin plugin : pluginRegistry.plugins()) {
            List<WebRouteContribution> routes = plugin.routes();
            if (!routes.isEmpty()) {
                register(plugin.id(), routes);
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

    private static void validate(WebRouteContribution route, String pluginId) {
        if (route == null) {
            throw new IllegalStateException("null route contribution (plugin: " + pluginId + ")");
        }
        String pattern = route.pathPattern();
        if (pattern == null || pattern.isBlank() || !pattern.startsWith("/")) {
            throw new IllegalStateException("invalid route path pattern: " + pattern
                    + " (plugin: " + pluginId + ")");
        }
        if (route.accessLevel() == null) {
            throw new IllegalStateException("route without access level: " + pattern
                    + " (plugin: " + pluginId + ")");
        }
    }

    /** 同一（模式, 方法集, 访问级别）三元组视为重复声明；方法集排序后参与键值。 */
    private static String routeKey(WebRouteContribution route) {
        return route.pathPattern() + "|" + route.accessLevel() + "|" + new TreeSet<>(route.methods());
    }
}
