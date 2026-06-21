package top.sywyar.pixivdownload.plugin;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.plugin.api.web.LandingContribution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 落点 / 入口注册中心。收集各<b>活动</b>插件（{@link PluginRegistry#plugins()}，禁用插件不贡献落点）的
 * {@link LandingContribution}，按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁。
 * <p>
 * 镜像 {@link NavigationRegistry} / {@link StartupRouteRegistry} 的注册形态，但承载的是<b>业务落点选择</b>
 * 这一独立概念：{@link #resolve(Audience)} 在服务某身份的全部落点中取 {@link LandingContribution#priority()}
 * 最小者，<b>不</b>复用导航 {@code order}。这把「UI 导航排序」与「业务落点选择」彻底拆开——第三方插件注册一个
 * order 更小的导航项不会改变邀请兑换落点，只有显式声明 {@link LandingContribution} 才参与落点竞争。
 * <p>
 * 落点只选择跳转目标、不扩大权限：后端鉴权仍由 {@code AuthFilter} / route access 按目标路由的访问策略执行。
 * 落点指向对该身份不可达的路由属配置错误，由 {@code LandingRegistryTest} 的可达性守卫捕获。
 */
@Component
public class LandingRegistry {

    /** 一条已注册落点及其声明方插件。 */
    public record RegisteredLanding(String pluginId, LandingContribution landing) {
    }

    private final Object lock = new Object();

    private volatile List<RegisteredLanding> snapshot = List.of();

    public LandingRegistry(PluginRegistry pluginRegistry) {
        for (PixivFeaturePlugin plugin : pluginRegistry.plugins()) {
            List<LandingContribution> landings = plugin.landings();
            if (!landings.isEmpty()) {
                register(plugin.id(), landings);
            }
        }
    }

    /** 以内置插件清单构建注册中心，供 Spring 上下文之外的入口（测试 / 启动期检查等）使用。 */
    public static LandingRegistry forBuiltInPlugins() {
        return new LandingRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
    }

    /**
     * 注册一个插件的全部落点。同一 pluginId 重复注册、落点非法或落点 id 与已注册项冲突都立即抛出，
     * 使应用启动失败而不是带病运行。id 要求全局唯一（便于诊断 / 去重）。
     */
    public void register(String pluginId, List<LandingContribution> landings) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalStateException("landing contribution without pluginId");
        }
        if (landings == null || landings.isEmpty()) {
            throw new IllegalStateException("empty landing contribution (plugin: " + pluginId + ")");
        }
        synchronized (lock) {
            if (snapshot.stream().anyMatch(registered -> registered.pluginId().equals(pluginId))) {
                throw new IllegalStateException("landings already registered for plugin: " + pluginId);
            }
            Set<String> ids = snapshot.stream()
                    .map(registered -> registered.landing().id())
                    .collect(Collectors.toCollection(HashSet::new));
            List<RegisteredLanding> next = new ArrayList<>(snapshot);
            for (LandingContribution item : landings) {
                validate(item, pluginId);
                if (!ids.add(item.id())) {
                    throw new IllegalStateException("duplicate landing id: "
                            + item.id() + " (plugin: " + pluginId + ")");
                }
                next.add(new RegisteredLanding(pluginId, item));
            }
            snapshot = List.copyOf(next);
        }
    }

    /**
     * 注销一个插件的全部落点。插件可以不声明任何落点，统一卸载流程会对每个插件调用，
     * 因此对未注册过的 pluginId 静默返回。
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            snapshot = snapshot.stream()
                    .filter(registered -> !registered.pluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        }
    }

    /** 按注册顺序返回全部落点的不可变快照。 */
    public List<RegisteredLanding> landings() {
        return snapshot;
    }

    /**
     * 解析给定身份的默认落点 href：在所有服务该身份（{@code audience} 相等）的已注册落点里取
     * {@link LandingContribution#priority()} 最小者（priority 相同按 id 稳定排序）；无任何匹配落点返回空，
     * 由调用方兜底（如邀请兑换回 {@code /login.html?inviteError=1}）。
     * <p>
     * 例：受邀访客（{@link Audience#INVITED_GUEST}）落点 —— gallery（priority 20）优先于 novel（priority 30）；
     * 禁用 gallery 后其落点不进活动快照、自动回退到 novel；两者都禁用则返回空。
     */
    public Optional<String> resolve(Audience audience) {
        if (audience == null) {
            return Optional.empty();
        }
        return snapshot.stream()
                .map(RegisteredLanding::landing)
                .filter(item -> item.audience() == audience)
                .min(Comparator.comparingInt(LandingContribution::priority)
                        .thenComparing(LandingContribution::id))
                .map(LandingContribution::href);
    }

    private static void validate(LandingContribution landing, String pluginId) {
        if (landing == null) {
            throw new IllegalStateException("null landing contribution (plugin: " + pluginId + ")");
        }
        if (!pluginId.equals(landing.pluginId())) {
            throw new IllegalStateException("landing pluginId mismatch: declared "
                    + landing.pluginId() + " under plugin " + pluginId);
        }
        if (landing.id() == null || landing.id().isBlank()) {
            throw new IllegalStateException("landing without id (plugin: " + pluginId + ")");
        }
        if (landing.audience() == null) {
            throw new IllegalStateException("landing without audience: "
                    + landing.id() + " (plugin: " + pluginId + ")");
        }
        String href = landing.href();
        if (href == null || href.isBlank() || !href.startsWith("/")) {
            throw new IllegalStateException("invalid landing href: " + href
                    + " (plugin: " + pluginId + ")");
        }
    }
}
