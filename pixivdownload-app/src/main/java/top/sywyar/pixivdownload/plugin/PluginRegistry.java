package top.sywyar.pixivdownload.plugin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 插件注册中心。按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁。
 * 注册中心是各下游 registry（schema / 路由 / 导航 / i18n / 静态资源）合并结果的唯一来源。
 * <p>
 * 「安装」与「启用」分离：{@link #allPlugins()} 是全部内置插件（安装态、随构造固定），
 * {@link #plugins()} 是<b>活动</b>（启用）插件的不可变快照——禁用的插件经
 * {@code plugins.<id>.enabled=false}（{@link PluginToggleProperties}）在构造期<b>不被注册进快照</b>，
 * 故各下游 registry（路由 / 导航 / i18n / 静态资源 / 调度来源 / 队列 / 标签页 / 落点）经 {@link #plugins()}
 * 自动排除禁用插件，其页面 / API / 导航因而不注册。<b>必选插件（{@link PixivFeaturePlugin#required()}：核心插件，
 * 以及覆写返回 {@code true} 的功能插件如下载工作台）永不可禁用</b>——即便开关写成 {@code false} 也照常注册。
 * schema 不随插件禁用而缺失：受管 schema 经 {@link #allPlugins()} 合并（见 {@code DatabaseSchemaRegistry}），
 * 即使插件被禁用其声明的表 / 列仍创建，已有数据保留。
 * <p>
 * 插件生命周期：应用启动后按注册顺序调用各<b>活动</b>插件 {@link PixivFeaturePlugin#start()}，
 * 关闭时按反序调用 {@link PixivFeaturePlugin#stop()}；禁用插件不进入活动快照，其生命周期方法不被调用。
 */
@Slf4j
@Component
public class PluginRegistry implements SmartLifecycle {

    /** 插件 id 规范：小写短横线，如 download-workbench。 */
    private static final Pattern PLUGIN_ID_PATTERN = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    private final Object lock = new Object();

    /** 全部内置插件（安装态），随构造固定；schema 合并经此读取，不受启用开关影响。 */
    private final List<PixivFeaturePlugin> installed;

    private volatile List<PixivFeaturePlugin> snapshot = List.of();
    private volatile boolean running;

    /** Spring 上下文外（{@code BuiltInPlugins.createAll()}、单元测试）构造：全部插件视为启用。 */
    public PluginRegistry(List<PixivFeaturePlugin> plugins) {
        this(plugins, new PluginToggleProperties());
    }

    /**
     * Spring 构造：按 {@code plugins.<id>.enabled} 决定哪些功能插件进入活动快照（禁用=不注册），
     * 必选插件（{@link PixivFeaturePlugin#required()}）永不可禁用。无论启用与否，全部插件都保留在
     * {@link #allPlugins()} 供 schema 合并。
     */
    @Autowired
    public PluginRegistry(List<PixivFeaturePlugin> plugins, PluginToggleProperties toggles) {
        List<PixivFeaturePlugin> all = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (PixivFeaturePlugin plugin : plugins) {
            String pluginId = plugin.id();
            if (pluginId == null || !PLUGIN_ID_PATTERN.matcher(pluginId).matches()) {
                throw new IllegalStateException("invalid plugin id: " + pluginId);
            }
            if (!seenIds.add(pluginId)) {
                throw new IllegalStateException("duplicate plugin id: " + pluginId);
            }
            all.add(plugin);
        }
        this.installed = List.copyOf(all);
        for (PixivFeaturePlugin plugin : all) {
            if (plugin.required() || toggles.isEnabled(plugin.id())) {
                register(plugin);
            }
        }
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

    /**
     * 按注册顺序返回<b>活动</b>（启用）插件的不可变快照。禁用的插件不在其中——下游 registry 经此
     * 聚合，因而自动排除禁用插件的路由 / 导航 / i18n / 静态资源等贡献。需要全部内置插件（含禁用）
     * 时用 {@link #allPlugins()}（如 schema 合并）。
     */
    public List<PixivFeaturePlugin> plugins() {
        return snapshot;
    }

    /**
     * 返回全部内置插件（安装态，含被禁用的），随构造固定、不受启用开关影响。
     * 供必须覆盖全部插件的场景使用——典型是 schema 合并（禁用插件的表 / 列仍需创建、数据保留）。
     */
    public List<PixivFeaturePlugin> allPlugins() {
        return installed;
    }

    /** 返回被禁用（安装但未进入活动快照）的插件。供维护任务按归属跳过禁用插件的任务等场景使用。 */
    public List<PixivFeaturePlugin> disabledPlugins() {
        Set<String> activeIds = snapshot.stream()
                .map(PixivFeaturePlugin::id)
                .collect(Collectors.toSet());
        return installed.stream()
                .filter(plugin -> !activeIds.contains(plugin.id()))
                .toList();
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
