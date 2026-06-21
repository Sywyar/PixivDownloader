package top.sywyar.pixivdownload.plugin;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 静态资源注册中心。收集各插件声明的 {@link StaticResourceContribution}，
 * 按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用。
 * <p>
 * 资源解析经声明方插件的 ClassLoader（{@link RegisteredStaticResource#classLoader()}），该 ClassLoader 由
 * {@link PluginRegistry} 的每条注册（{@link PluginRegistry.RegisteredPlugin#classLoader()}）权威提供：内置插件
 * 是应用 ClassLoader（解析结果与 Spring Boot 默认 {@code classpath:/static} 整体放行一致），外置插件是发现桥接
 * 捕获的该插件自身 ClassLoader。故本注册中心消费 {@link PluginRegistry#registeredPlugins()}（带来源 + ClassLoader），
 * <b>不</b>从 {@code plugin.getClass().getClassLoader()} 自行推导——后者对「插件实例由共享 / 父 ClassLoader 创建」
 * 的外置插件会误解析到错误的 ClassLoader（见 {@code StaticResourceConfig}）。
 * <p>
 * 对外公开路径前缀（{@code publicPathPrefix}）全局唯一：两个声明指向同一前缀会让
 * 资源解析不确定，故前缀冲突（跨插件与同一批次内）一律在注册期拒绝，使应用启动失败而不是带病运行。
 */
@Component
public class StaticResourceRegistry {

    /** 一条已注册静态资源、声明方插件与解析用 ClassLoader。 */
    public record RegisteredStaticResource(String pluginId, StaticResourceContribution contribution,
                                           ClassLoader classLoader) {
    }

    private final Object lock = new Object();

    private volatile List<RegisteredStaticResource> snapshot = List.of();

    public StaticResourceRegistry(PluginRegistry pluginRegistry) {
        for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.registeredPlugins()) {
            PixivFeaturePlugin plugin = registered.plugin();
            List<StaticResourceContribution> resources = plugin.staticResources();
            if (!resources.isEmpty()) {
                register(plugin.id(), registered.classLoader(), resources);
            }
        }
    }

    /**
     * 注册一个插件声明的全部静态资源。同一 pluginId 重复注册、声明非法，
     * 或对外路径前缀与已注册项冲突都立即抛出，使应用启动失败而不是带病运行。
     */
    public void register(String pluginId, ClassLoader classLoader, List<StaticResourceContribution> resources) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalStateException("static resource contribution without pluginId");
        }
        if (classLoader == null) {
            throw new IllegalStateException("static resource contribution without classLoader (plugin: "
                    + pluginId + ")");
        }
        if (resources == null || resources.isEmpty()) {
            throw new IllegalStateException("empty static resource contribution (plugin: " + pluginId + ")");
        }
        synchronized (lock) {
            if (snapshot.stream().anyMatch(registered -> registered.pluginId().equals(pluginId))) {
                throw new IllegalStateException("static resources already registered for plugin: " + pluginId);
            }
            Set<String> prefixes = snapshot.stream()
                    .map(registered -> registered.contribution().publicPathPrefix())
                    .collect(Collectors.toCollection(HashSet::new));
            List<RegisteredStaticResource> next = new ArrayList<>(snapshot);
            for (StaticResourceContribution contribution : resources) {
                validate(contribution, pluginId);
                if (!prefixes.add(contribution.publicPathPrefix())) {
                    throw new IllegalStateException("duplicate static resource prefix: "
                            + contribution.publicPathPrefix() + " (plugin: " + pluginId + ")");
                }
                next.add(new RegisteredStaticResource(pluginId, contribution, classLoader));
            }
            snapshot = List.copyOf(next);
        }
    }

    /**
     * 注销一个插件的全部静态资源。插件可以不声明任何静态资源，统一卸载流程会对每个插件调用，
     * 因此对未注册过的 pluginId 静默返回。
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            snapshot = snapshot.stream()
                    .filter(registered -> !registered.pluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        }
    }

    /** 按注册顺序返回全部已注册静态资源的不可变快照。 */
    public List<RegisteredStaticResource> resources() {
        return snapshot;
    }

    private static void validate(StaticResourceContribution contribution, String pluginId) {
        if (contribution == null) {
            throw new IllegalStateException("null static resource contribution (plugin: " + pluginId + ")");
        }
        if (!pluginId.equals(contribution.pluginId())) {
            throw new IllegalStateException("static resource pluginId mismatch: declared "
                    + contribution.pluginId() + " under plugin " + pluginId);
        }
        String location = contribution.classpathLocation();
        if (location == null || location.isBlank()
                || !location.startsWith("classpath:") || !location.endsWith("/")) {
            throw new IllegalStateException("invalid static resource classpath location: " + location
                    + " (plugin: " + pluginId + ")");
        }
        String prefix = contribution.publicPathPrefix();
        if (prefix == null || prefix.isBlank() || !prefix.startsWith("/") || !prefix.endsWith("/")) {
            throw new IllegalStateException("invalid static resource public path prefix: " + prefix
                    + " (plugin: " + pluginId + ")");
        }
    }
}
