package top.sywyar.pixivdownload.plugin;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 导航注册中心。收集各插件的 {@link NavigationContribution}，
 * 按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁
 * （{@code /api/navigation} 在每次请求上读取）。
 * <p>
 * 快照按注册顺序返回，可见性过滤与按 {@code order} 排序由消费端
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
     * 或导航 href 与已注册项冲突都立即抛出，使应用启动失败而不是带病运行。
     * <p>
     * id 与 href 均要求全局唯一：跨插件用不同 id 指向同一 href 会让 {@code /api/navigation}
     * 返回指向同一页面的重复入口，故 href 冲突（跨插件与同一批次内）一律拒绝，避免非确定行为。
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
            Set<String> hrefs = snapshot.stream()
                    .map(registered -> registered.navigation().href())
                    .collect(Collectors.toCollection(HashSet::new));
            List<RegisteredNavigation> next = new ArrayList<>(snapshot);
            for (NavigationContribution item : navigation) {
                validate(item, pluginId);
                if (!ids.add(item.id())) {
                    throw new IllegalStateException("duplicate navigation id: "
                            + item.id() + " (plugin: " + pluginId + ")");
                }
                if (!hrefs.add(item.href())) {
                    throw new IllegalStateException("duplicate navigation href: "
                            + item.href() + " (plugin: " + pluginId + ")");
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
