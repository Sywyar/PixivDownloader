package top.sywyar.pixivdownload.plugin.registry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.TabContribution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 下载工作台标签页兼容门面。生产读路径投影 {@link DownloadExtensionRegistry} 的单一 owner 原子快照；
 * 保留旧构造器与可逆写方法，供独立兼容测试使用。
 * <p>
 * 旧实现收集各插件的 {@link TabContribution}，
 * 按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁。
 * <p>
 * 镜像 {@link NavigationRegistry} / {@link QueueTypeRegistry}：标签页 id（{@code tabId}）全局唯一，
 * 重复立即抛出使应用启动失败。
 */
@Component
public class DownloadTabRegistry {

    /** 一条已注册标签页及其声明方插件。 */
    public record RegisteredTab(String pluginId, TabContribution tab) {
    }

    private final Object lock = new Object();

    private final DownloadExtensionRegistry extensionRegistry;

    private volatile List<RegisteredTab> snapshot = List.of();

    @Autowired
    public DownloadTabRegistry(DownloadExtensionRegistry extensionRegistry) {
        this.extensionRegistry = extensionRegistry;
    }

    public DownloadTabRegistry(PluginRegistry pluginRegistry) {
        this.extensionRegistry = null;
        for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.registeredPlugins()) {
            PixivFeaturePlugin plugin = registered.plugin();
            List<TabContribution> tabs = plugin.downloadTabs();
            if (!tabs.isEmpty()) {
                register(registered.id(), tabs);
            }
        }
    }

    /**
     * 注册一个插件的全部标签页。同一 pluginId 重复注册、标签页非法、标签页 id 与已注册项冲突都立即抛出。
     */
    public void register(String pluginId, List<TabContribution> tabs) {
        requireLegacyMutationMode();
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalStateException("download tab contribution without pluginId");
        }
        if (tabs == null || tabs.isEmpty()) {
            throw new IllegalStateException("empty download tab contribution (plugin: " + pluginId + ")");
        }
        synchronized (lock) {
            if (snapshot.stream().anyMatch(registered -> registered.pluginId().equals(pluginId))) {
                throw new IllegalStateException("download tabs already registered for plugin: " + pluginId);
            }
            Set<String> ids = snapshot.stream()
                    .map(registered -> registered.tab().tabId())
                    .collect(Collectors.toCollection(HashSet::new));
            List<RegisteredTab> next = new ArrayList<>(snapshot);
            for (TabContribution item : tabs) {
                validate(item, pluginId);
                if (!ids.add(item.tabId())) {
                    throw new IllegalStateException("duplicate download tab id: "
                            + item.tabId() + " (plugin: " + pluginId + ")");
                }
                next.add(new RegisteredTab(pluginId, item));
            }
            snapshot = List.copyOf(next);
        }
    }

    /**
     * 注销一个插件的全部标签页。插件可以不声明任何标签页，统一卸载流程会对每个插件调用，
     * 因此对未注册过的 pluginId 静默返回。
     */
    public void unregister(String pluginId) {
        requireLegacyMutationMode();
        synchronized (lock) {
            snapshot = snapshot.stream()
                    .filter(registered -> !registered.pluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        }
    }

    /** 按注册顺序返回全部标签页的不可变快照。 */
    public List<RegisteredTab> tabs() {
        if (extensionRegistry != null) {
            return extensionRegistry.snapshot().tabs().stream()
                    .map(registered -> new RegisteredTab(
                            registered.owner().featurePluginId(),
                            new TabContribution(
                                    registered.tab().pluginId(),
                                    registered.tab().tabId(),
                                    registered.tab().order(),
                                    registered.supportedQueueTypes())))
                    .toList();
        }
        return snapshot;
    }

    private void requireLegacyMutationMode() {
        if (extensionRegistry != null) {
            throw new UnsupportedOperationException(
                    "download extension mutations must use DownloadExtensionRegistry publications");
        }
    }

    private static void validate(TabContribution tab, String pluginId) {
        if (tab == null) {
            throw new IllegalStateException("null download tab contribution (plugin: " + pluginId + ")");
        }
        DownloadExtensionRegistry.validateTab(tab, pluginId);
    }
}
