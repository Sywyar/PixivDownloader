package top.sywyar.pixivdownload.plugin.registry;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import top.sywyar.pixivdownload.plugin.web.NavigationController;

/**
 * 导航注册中心。收集各插件的 {@link NavigationContribution}，
 * 按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁
 * （{@code /api/navigation} 在每次请求上读取）。
 * <p>
 * 快照按注册顺序返回，可见性过滤与按 {@code priority} 排序由消费端
 * （{@code NavigationController}）完成。本注册中心只负责聚合 {@code /api/navigation}
 * 的导航数据来源；各页面目前仍渲染各自的静态导航，是否逐页改用 {@code /api/navigation}
 * 由各页面自行决定、不属本注册中心职责。
 */
@Component
public class NavigationRegistry {

    /** 一条已注册导航项及其声明方插件。 */
    public record RegisteredNavigation(String pluginId, NavigationContribution navigation) {
    }

    private final Object lock = new Object();

    private volatile List<RegisteredNavigation> snapshot = List.of();

    public NavigationRegistry(PluginRegistry pluginRegistry) {
        for (PixivFeaturePlugin plugin : pluginRegistry.plugins()) {
            List<NavigationContribution> navigation = plugin.navigation();
            if (!navigation.isEmpty()) {
                register(plugin.id(), navigation);
            }
        }
    }

    /**
     * 注册一个插件的全部导航项。同一 pluginId 重复注册、导航项非法、导航 id 与已注册项冲突，
     * 或同一 placement 内 href 与已注册项冲突都立即抛出，使应用启动失败而不是带病运行。
     * <p>
     * <b>id 全局唯一</b>：每条导航项是一个逻辑入口（可经 {@link NavigationContribution#placements()}
     * 同时进入多个 placement），故 id 跨插件不可重名，便于诊断 / 去重 / 前端 isAvailable。
     * <b>href 在每个 placement 内唯一</b>：同一菜单（placement）里不可出现两条指向同一页面的入口；
     * 但不同 placement 可复用同一 href（如类型切换 tab 与疑似重复页图标都指向画廊），故 href 冲突按 placement 判定、不做全局判定。
     */
    public void register(String pluginId, List<NavigationContribution> navigation) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalStateException("navigation contribution without pluginId");
        }
        if (navigation == null || navigation.isEmpty()) {
            throw new IllegalStateException("empty navigation contribution (plugin: " + pluginId + ")");
        }
        synchronized (lock) {
            if (snapshot.stream().anyMatch(registered -> registered.pluginId().equals(pluginId))) {
                throw new IllegalStateException("navigation already registered for plugin: " + pluginId);
            }
            Set<String> ids = snapshot.stream()
                    .map(registered -> registered.navigation().id())
                    .collect(Collectors.toCollection(HashSet::new));
            // placement → 该 placement 内已用的 href 集合（href 仅在 placement 内要求唯一）。
            Map<String, Set<String>> hrefsByPlacement = new HashMap<>();
            for (RegisteredNavigation registered : snapshot) {
                NavigationContribution nav = registered.navigation();
                for (String placement : nav.placements()) {
                    hrefsByPlacement.computeIfAbsent(placement, k -> new HashSet<>()).add(nav.href());
                }
            }
            List<RegisteredNavigation> next = new ArrayList<>(snapshot);
            for (NavigationContribution item : navigation) {
                validate(item, pluginId);
                if (!ids.add(item.id())) {
                    throw new IllegalStateException("duplicate navigation id: "
                            + item.id() + " (plugin: " + pluginId + ")");
                }
                for (String placement : item.placements()) {
                    if (!hrefsByPlacement.computeIfAbsent(placement, k -> new HashSet<>()).add(item.href())) {
                        throw new IllegalStateException("duplicate navigation href: " + item.href()
                                + " in placement " + placement + " (plugin: " + pluginId + ")");
                    }
                }
                next.add(new RegisteredNavigation(pluginId, item));
            }
            snapshot = List.copyOf(next);
        }
    }

    /**
     * 注销一个插件的全部导航项。插件可以不声明任何导航项，统一卸载流程会对每个插件调用，
     * 因此对未注册过的 pluginId 静默返回。
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            snapshot = snapshot.stream()
                    .filter(registered -> !registered.pluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        }
    }

    /** 按注册顺序返回全部导航项的不可变快照。 */
    public List<RegisteredNavigation> navigation() {
        return snapshot;
    }

    private static void validate(NavigationContribution navigation, String pluginId) {
        if (navigation == null) {
            throw new IllegalStateException("null navigation contribution (plugin: " + pluginId + ")");
        }
        if (navigation.id() == null || navigation.id().isBlank()) {
            throw new IllegalStateException("navigation without id (plugin: " + pluginId + ")");
        }
        if (navigation.placements() == null || navigation.placements().isEmpty()) {
            throw new IllegalStateException("navigation without placement: "
                    + navigation.id() + " (plugin: " + pluginId + ")");
        }
        for (String placement : navigation.placements()) {
            if (placement == null || placement.isBlank()) {
                throw new IllegalStateException("navigation with blank placement: "
                        + navigation.id() + " (plugin: " + pluginId + ")");
            }
        }
        if (navigation.labelI18nKey() == null || navigation.labelI18nKey().isBlank()) {
            throw new IllegalStateException("navigation without label i18n key: "
                    + navigation.id() + " (plugin: " + pluginId + ")");
        }
        if (navigation.href() == null || navigation.href().isBlank()) {
            throw new IllegalStateException("navigation without href: "
                    + navigation.id() + " (plugin: " + pluginId + ")");
        }
        if (navigation.visibleTo() == null) {
            throw new IllegalStateException("navigation without access level: "
                    + navigation.id() + " (plugin: " + pluginId + ")");
        }
    }
}
