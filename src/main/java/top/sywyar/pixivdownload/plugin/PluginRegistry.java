package top.sywyar.pixivdownload.plugin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 插件注册中心。按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁。
 * 注册中心是各下游 registry（schema / 路由 / 导航 / i18n / 静态资源）合并结果的唯一来源。
 * <p>
 * 插件生命周期：应用启动后按注册顺序调用各插件 {@link PixivFeaturePlugin#start()}，
 * 关闭时按反序调用 {@link PixivFeaturePlugin#stop()}。
 */
@Slf4j
@Component
public class PluginRegistry implements SmartLifecycle {

    /** 插件 id 规范：小写短横线，如 download-workbench。 */
    private static final Pattern PLUGIN_ID_PATTERN = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    private final Object lock = new Object();

    private volatile List<PixivFeaturePlugin> snapshot = List.of();
    private volatile boolean running;

    public PluginRegistry(List<PixivFeaturePlugin> plugins) {
        plugins.forEach(this::register);
    }

    /**
     * 注册插件。id 重复或不符合规范立即抛出，使应用启动失败而不是带病运行。
     */
    public void register(PixivFeaturePlugin plugin) {
        String pluginId = plugin.id();
        if (pluginId == null || !PLUGIN_ID_PATTERN.matcher(pluginId).matches()) {
            throw new IllegalStateException("invalid plugin id: " + pluginId);
        }
        synchronized (lock) {
            if (containsId(snapshot, pluginId)) {
                throw new IllegalStateException("duplicate plugin id: " + pluginId);
            }
            List<PixivFeaturePlugin> next = new ArrayList<>(snapshot);
            next.add(plugin);
            snapshot = List.copyOf(next);
        }
    }

    /**
     * 注销插件，注销后注册中心状态与该插件从未注册过一致。
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            if (!containsId(snapshot, pluginId)) {
                throw new IllegalArgumentException("unknown plugin id: " + pluginId);
            }
            snapshot = snapshot.stream()
                    .filter(plugin -> !plugin.id().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        }
    }

    /** 按注册顺序返回全部插件的不可变快照。 */
    public List<PixivFeaturePlugin> plugins() {
        return snapshot;
    }

    public Optional<PixivFeaturePlugin> find(String pluginId) {
        return snapshot.stream().filter(plugin -> plugin.id().equals(pluginId)).findFirst();
    }

    @Override
    public void start() {
        List<PixivFeaturePlugin> plugins = snapshot;
        plugins.forEach(PixivFeaturePlugin::start);
        running = true;
        log.info(MessageBundles.get("plugin.log.started", plugins.size(),
                plugins.stream().map(PixivFeaturePlugin::id).collect(Collectors.joining(", "))));
    }

    @Override
    public void stop() {
        List<PixivFeaturePlugin> plugins = snapshot;
        for (int i = plugins.size() - 1; i >= 0; i--) {
            PixivFeaturePlugin plugin = plugins.get(i);
            try {
                plugin.stop();
            } catch (Exception e) {
                log.warn(MessageBundles.get("plugin.log.stop-failed", plugin.id(), e.getMessage()), e);
            }
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static boolean containsId(List<PixivFeaturePlugin> plugins, String pluginId) {
        return plugins.stream().anyMatch(plugin -> plugin.id().equals(pluginId));
    }
}
