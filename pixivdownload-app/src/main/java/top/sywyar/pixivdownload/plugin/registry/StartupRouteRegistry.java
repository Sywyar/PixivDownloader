package top.sywyar.pixivdownload.plugin.registry;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;

/**
 * 默认启动落点注册中心。收集各插件的 {@link StartupRouteContribution}，
 * 按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁
 * （{@code AuthFilter} 处理 {@code /redirect} 时读取）。
 * <p>
 * {@code /redirect} 的落点选择由 {@link #resolvePath(StartupRouteContext)} 承载：先取当前启动上下文的
 * 首选落点，缺失时回退到 {@code order} 最小的已注册落点；全部缺失返回空，由调用方兜底。
 */
@Component
public class StartupRouteRegistry {

    /** 一条已注册启动落点及其声明方插件。 */
    public record RegisteredStartupRoute(String pluginId, StartupRouteContribution route) {
    }

    private final Object lock = new Object();

    private volatile List<RegisteredStartupRoute> snapshot = List.of();

    public StartupRouteRegistry(PluginRegistry pluginRegistry) {
        for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.registeredPlugins()) {
            PixivFeaturePlugin plugin = registered.plugin();
            List<StartupRouteContribution> routes = plugin.startupRoutes();
            if (!routes.isEmpty()) {
                register(registered.id(), routes);
            }
        }
    }

    /** 以内置插件清单构建注册中心，供 Spring 上下文之外的入口（测试 / 启动期检查等）使用。 */
    public static StartupRouteRegistry forBuiltInPlugins() {
        return new StartupRouteRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
    }

    /**
     * 注册一个插件的全部启动落点。同一 pluginId 重复注册或落点非法立即抛出，
     * 使应用启动失败而不是带病运行。
     */
    public void register(String pluginId, List<StartupRouteContribution> routes) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalStateException("startup route contribution without pluginId");
        }
        if (routes == null || routes.isEmpty()) {
            throw new IllegalStateException("empty startup route contribution (plugin: " + pluginId + ")");
        }
        synchronized (lock) {
            if (snapshot.stream().anyMatch(registered -> registered.pluginId().equals(pluginId))) {
                throw new IllegalStateException("startup routes already registered for plugin: " + pluginId);
            }
            List<RegisteredStartupRoute> next = new ArrayList<>(snapshot);
            for (StartupRouteContribution route : routes) {
                validate(route, pluginId);
                next.add(new RegisteredStartupRoute(pluginId, route));
            }
            snapshot = List.copyOf(next);
        }
    }

    /**
     * 注销一个插件的全部启动落点。插件可以不声明任何落点，统一卸载流程会对每个插件调用，
     * 因此对未注册过的 pluginId 静默返回。
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            snapshot = snapshot.stream()
                    .filter(registered -> !registered.pluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        }
    }

    /** 按注册顺序返回全部启动落点的不可变快照。 */
    public List<RegisteredStartupRoute> startupRoutes() {
        return snapshot;
    }

    /**
     * 解析默认落点：优先返回匹配启动上下文的落点，当前上下文无声明时回退到 {@code order}
     * 最小的已注册落点（order 相同按注册顺序）；无任何落点则返回空。
     */
    public Optional<String> resolvePath(StartupRouteContext context) {
        List<RegisteredStartupRoute> current = snapshot;
        if (context != null) {
            Optional<String> preferred = current.stream()
                    .filter(registered -> registered.route().preferredContexts().contains(context))
                    .min(Comparator.comparingInt(registered -> registered.route().order()))
                    .map(registered -> registered.route().path());
            if (preferred.isPresent()) {
                return preferred;
            }
        }
        return fallback(current);
    }

    /**
     * 解析默认落点：优先返回首选插件声明的落点，首选插件未声明 / 未启用时回退到 {@code order}
     * 最小的已注册落点（order 相同按注册顺序）；无任何落点则返回空。
     *
     * @param preferredPluginId 当前模式的首选插件 id（可空 → 直接走回退）
     */
    public Optional<String> resolvePath(String preferredPluginId) {
        List<RegisteredStartupRoute> current = snapshot;
        if (preferredPluginId != null) {
            Optional<String> preferred = current.stream()
                    .filter(registered -> registered.pluginId().equals(preferredPluginId))
                    .min(Comparator.comparingInt(registered -> registered.route().order()))
                    .map(registered -> registered.route().path());
            if (preferred.isPresent()) {
                return preferred;
            }
        }
        return fallback(current);
    }

    private static Optional<String> fallback(List<RegisteredStartupRoute> current) {
        return current.stream()
                .min(Comparator.comparingInt(registered -> registered.route().order()))
                .map(registered -> registered.route().path());
    }

    private static void validate(StartupRouteContribution route, String pluginId) {
        if (route == null) {
            throw new IllegalStateException("null startup route contribution (plugin: " + pluginId + ")");
        }
        String path = route.path();
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalStateException("invalid startup route path: " + path
                    + " (plugin: " + pluginId + ")");
        }
    }
}
