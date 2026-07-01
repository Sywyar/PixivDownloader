package top.sywyar.pixivdownload.plugin.registry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PixivPluginDiscoveryBridge;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;

/**
 * 插件注册中心。按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁。
 * 注册中心是各下游 registry（schema / 路由 / 导航 / i18n / 静态资源）合并结果的唯一来源。
 * <p>
 * <b>两类来源。</b>注册中心同时承载两类插件来源（{@link PluginSource}）：第一类是随 boot jar 编译进来、由各
 * {@code XxxPluginConfiguration} 装配的<b>内置</b>插件（{@link PluginSource#BUILT_IN}，应用 classloader 加载）；
 * 第二类是从 {@code plugins/} 目录由 PF4J 加载、经发现桥接（{@code PixivPluginDiscoveryBridge}）接入的<b>外置</b>
 * 插件（{@link PluginSource#EXTERNAL}，各自插件 classloader 加载）。两类来源在注册中心里行为一致——都进入活动快照、
 * 都参与 schema / 路由 / 导航 / i18n / 静态资源合并；每条注册都保留来源与解析用 classloader（{@link RegisteredPlugin}），
 * 下游静态资源 / i18n 解析据此 classloader-aware。注册顺序稳定：<b>内置插件优先</b>（按装配顺序），外置插件追加在后、
 * 按 {@link PixivFeaturePlugin#id()} 排序。<b>pluginId 全局唯一</b>：内置与外置之间、外置彼此之间 id 冲突一律在构造期
 * fail-fast 并指出冲突双方来源（不静默覆盖）。外置插件包加载 / 发现失败（坏包、主类未实现入口契约等）只记诊断、不致命。
 * <p>
 * 「安装」与「启用」分离：{@link #allPlugins()} 是全部插件（安装态，含内置与外置；构造期建立，运行期经
 * {@link #register} / {@link #unregister} 可逆增删——注销后该 id 从安装态与活动快照<b>一并</b>移除，与从未注册过
 * 一致），{@link #plugins()} 是<b>活动</b>（启用）插件的不可变快照——禁用的插件经
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

    /** 一条已注册插件、其来源与解析用 classloader。 */
    public record RegisteredPlugin(PixivFeaturePlugin plugin, PluginSource source, ClassLoader classLoader,
                                   String packageId, long generation) {

        public RegisteredPlugin {
            if (plugin == null) {
                throw new IllegalStateException("registered plugin must not be null");
            }
            if (source == null) {
                throw new IllegalStateException("registered plugin source must not be null (plugin: "
                        + plugin.id() + ")");
            }
            if (classLoader == null) {
                throw new IllegalStateException("registered plugin classLoader must not be null (plugin: "
                        + plugin.id() + ")");
            }
            if (packageId == null || packageId.isBlank()) {
                throw new IllegalStateException("registered plugin packageId must not be blank (plugin: "
                        + plugin.id() + ")");
            }
        }

        public RegisteredPlugin(PixivFeaturePlugin plugin, PluginSource source, ClassLoader classLoader) {
            this(plugin, source, classLoader, plugin.id(), 0L);
        }

        public String id() {
            return plugin.id();
        }
    }

    private final Object lock = new Object();

    /** 启用开关：活动成员判定的事实源。构造期与运行期 {@link #register} 都据此决定插件是否进入活动快照。 */
    private final PluginToggleProperties toggles;

    /**
     * 全部插件（安装态，内置 + 外置）。构造期建立，运行期 {@link #register} 追加、{@link #unregister} 移除——
     * 注销后该插件不再出现在安装态中（与从未注册过一致）。读路径走不可变快照：变更时整体替换引用（读侧无锁）。
     * schema 合并经此读取，不受启用开关影响（禁用插件仍在安装态，注销插件不在）。
     */
    private volatile List<RegisteredPlugin> installed = List.of();
    private volatile List<PixivFeaturePlugin> installedPlugins = List.of();

    private volatile List<RegisteredPlugin> snapshot = List.of();
    private volatile List<PixivFeaturePlugin> snapshotPlugins = List.of();
    private volatile boolean running;

    /** Spring 上下文外（{@code BuiltInPlugins.createAll()}、单元测试）构造：全部插件视为启用、无外置插件。 */
    public PluginRegistry(List<PixivFeaturePlugin> plugins) {
        this(plugins, new PluginToggleProperties(), PluginDiscoveryResult.empty());
    }

    /** 显式开关 + 无外置插件构造（单元测试用）。 */
    public PluginRegistry(List<PixivFeaturePlugin> plugins, PluginToggleProperties toggles) {
        this(plugins, toggles, PluginDiscoveryResult.empty());
    }

    /**
     * Spring 构造：内置插件经注入的 {@code List<PixivFeaturePlugin>} 提供，外置插件经发现桥接的
     * {@link PluginDiscoveryResult} 提供（无 {@code plugins/} 目录 / 无外置插件时为空）。按 {@code plugins.<id>.enabled}
     * 决定哪些功能插件进入活动快照（禁用=不注册），必选插件永不可禁用。无论启用与否，全部插件都保留在
     * {@link #allPlugins()} 供 schema 合并。
     */
    @Autowired
    public PluginRegistry(List<PixivFeaturePlugin> plugins, PluginToggleProperties toggles,
                          ObjectProvider<PluginDiscoveryResult> externalDiscovery) {
        this(plugins, toggles, externalDiscovery.getIfAvailable(PluginDiscoveryResult::empty));
    }

    /** 全量构造（内置 + 外置发现结果）。Spring 经上面的 {@link ObjectProvider} 构造器走到这里；单元测试也可直接构造双来源注册中心。 */
    public PluginRegistry(List<PixivFeaturePlugin> builtInPlugins, PluginToggleProperties toggles,
                          PluginDiscoveryResult external) {
        this.toggles = toggles;
        Map<String, RegisteredPlugin> byId = new LinkedHashMap<>();
        List<RegisteredPlugin> all = new ArrayList<>();
        // 内置插件优先，保持装配顺序
        for (PixivFeaturePlugin plugin : builtInPlugins) {
            addInstalled(byId, all,
                    new RegisteredPlugin(plugin, PluginSource.BUILT_IN, classLoaderOf(plugin)),
                    "built-in source");
        }
        // 外置插件追加在后，按功能插件 id 排序（注册顺序稳定）
        List<DiscoveredFeaturePlugin> externalSorted = external.discovered().stream()
                .sorted(Comparator.comparing(DiscoveredFeaturePlugin::featurePluginId))
                .toList();
        for (DiscoveredFeaturePlugin discovered : externalSorted) {
            addInstalled(byId, all,
                    new RegisteredPlugin(discovered.plugin(), PluginSource.EXTERNAL, discovered.classLoader(),
                            discovered.sourcePluginId(), discovered.generation()),
                    "external plugin package '" + discovered.sourcePluginId() + "'");
        }
        logExternalDiscovery(external);

        setInstalled(List.copyOf(all));

        List<RegisteredPlugin> active = new ArrayList<>();
        for (RegisteredPlugin registered : all) {
            if (isActive(registered)) {
                active.add(registered);
            }
        }
        setSnapshot(List.copyOf(active));
    }

    /** 校验 id 规范并把插件加入安装清单；id 重复（跨来源）立即抛出并指出冲突双方来源，使应用启动失败而不是带病运行。 */
    private static void addInstalled(Map<String, RegisteredPlugin> byId, List<RegisteredPlugin> all,
                                     RegisteredPlugin candidate, String origin) {
        String pluginId = candidate.id();
        if (pluginId == null || !PLUGIN_ID_PATTERN.matcher(pluginId).matches()) {
            throw new IllegalStateException("invalid plugin id: " + pluginId + " (from " + origin + ")");
        }
        RegisteredPlugin existing = byId.get(pluginId);
        if (existing != null) {
            throw new IllegalStateException("duplicate plugin id '" + pluginId
                    + "': already registered from " + existing.source() + " source; conflicting registration from "
                    + origin);
        }
        byId.put(pluginId, candidate);
        all.add(candidate);
    }

    private static void logExternalDiscovery(PluginDiscoveryResult external) {
        if (!external.discovered().isEmpty()) {
            log.info("PluginRegistry: bridged {} external feature plugin(s): {}",
                    external.discoveredCount(),
                    external.discovered().stream().map(DiscoveredFeaturePlugin::featurePluginId)
                            .collect(Collectors.joining(", ")));
        }
        for (PluginLoadFailure failure : external.failures()) {
            log.warn("PluginRegistry: external plugin discovery failure [{}]: {}",
                    failure.source(), failure.reason());
        }
    }

    /**
     * 注册一个内置来源插件（{@link PluginSource#BUILT_IN}），解析用应用 classloader。
     * id 重复或不符合规范立即抛出，使应用启动失败而不是带病运行。
     */
    public void register(PixivFeaturePlugin plugin) {
        register(plugin, PluginSource.BUILT_IN, classLoaderOf(plugin));
    }

    /**
     * 注册一个带来源与解析用 classloader 的插件（供外置插件接入与可逆性测试使用）。插件先进入安装态
     * （{@link #allPlugins()}，供 schema 合并 / {@link #disabledPlugins()} 计算），再按启用开关决定是否进入活动
     * 快照（必选插件恒活动，可选插件按 {@code plugins.<id>.enabled}），使 register -> unregister -> register 可逆。
     * id 与安装态或活动快照中已有插件重复，或不符合规范，立即抛出。
     */
    public void register(PixivFeaturePlugin plugin, PluginSource source, ClassLoader classLoader) {
        register(new RegisteredPlugin(plugin, source, classLoader));
    }

    /** 注册一条带包身份和 generation 的完整运行时记录。 */
    public void register(RegisteredPlugin candidate) {
        String pluginId = candidate.id();
        if (pluginId == null || !PLUGIN_ID_PATTERN.matcher(pluginId).matches()) {
            throw new IllegalStateException("invalid plugin id: " + pluginId);
        }
        synchronized (lock) {
            if (containsId(installed, pluginId) || containsId(snapshot, pluginId)) {
                throw new IllegalStateException("duplicate plugin id: " + pluginId);
            }
            List<RegisteredPlugin> nextInstalled = new ArrayList<>(installed);
            nextInstalled.add(candidate);
            setInstalled(List.copyOf(nextInstalled));
            if (isActive(candidate)) {
                List<RegisteredPlugin> nextActive = new ArrayList<>(snapshot);
                nextActive.add(candidate);
                setSnapshot(List.copyOf(nextActive));
            }
        }
    }

    /**
     * 注销插件：从安装态（{@link #allPlugins()}）与活动快照（{@link #plugins()} / {@link #registeredPlugins()}）
     * <b>同时</b>移除，注销后注册中心状态与该插件从未注册过一致——{@link #allPlugins()}、{@link #disabledPlugins()}、
     * {@link #source(String)} 都不再含该 id，schema 合并也不再看到它的贡献。安装态或活动快照任一含该 id 即可注销
     * （兼容注销被禁用但仍安装的插件）；都不含则抛出。
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            if (!containsId(installed, pluginId) && !containsId(snapshot, pluginId)) {
                throw new IllegalArgumentException("unknown plugin id: " + pluginId);
            }
            setInstalled(installed.stream()
                    .filter(registered -> !registered.id().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf)));
            setSnapshot(snapshot.stream()
                    .filter(registered -> !registered.id().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf)));
        }
    }

    /**
     * 按注册顺序返回<b>活动</b>（启用）插件的不可变快照。禁用的插件不在其中——下游 registry 经此
     * 聚合，因而自动排除禁用插件的路由 / 导航 / i18n / 静态资源等贡献。需要全部插件（含禁用）
     * 时用 {@link #allPlugins()}（如 schema 合并）；需要来源 / classloader 信息时用 {@link #registeredPlugins()}。
     */
    public List<PixivFeaturePlugin> plugins() {
        return snapshotPlugins;
    }

    /** 按注册顺序返回<b>活动</b>插件的源信息快照（插件 + 来源 + 解析用 classloader），不可变。 */
    public List<RegisteredPlugin> registeredPlugins() {
        return snapshot;
    }

    /**
     * 返回全部插件（安装态，内置 + 外置，含被禁用的），不受启用开关影响。安装态在构造期建立、随
     * {@link #register} / {@link #unregister} 可逆增删（禁用插件仍在，注销插件不在）。供必须覆盖全部已安装插件
     * 的场景使用——典型是 schema 合并（禁用插件的表 / 列仍需创建、数据保留；注销插件的 schema 不再合并）。
     */
    public List<PixivFeaturePlugin> allPlugins() {
        return installedPlugins;
    }

    /** 返回被禁用（安装但未进入活动快照）的插件。供维护任务按归属跳过禁用插件的任务等场景使用。 */
    public List<PixivFeaturePlugin> disabledPlugins() {
        List<RegisteredPlugin> activeNow = snapshot;
        List<RegisteredPlugin> installedNow = installed;
        java.util.Set<String> activeIds = activeNow.stream()
                .map(RegisteredPlugin::id)
                .collect(Collectors.toSet());
        return installedNow.stream()
                .filter(registered -> !activeIds.contains(registered.id()))
                .map(RegisteredPlugin::plugin)
                .toList();
    }

    public Optional<PixivFeaturePlugin> find(String pluginId) {
        return snapshot.stream().filter(registered -> registered.id().equals(pluginId))
                .map(RegisteredPlugin::plugin).findFirst();
    }

    /** 活动插件的来源（{@link PluginSource}），未注册（或已禁用）时为空。 */
    public Optional<PluginSource> source(String pluginId) {
        return snapshot.stream().filter(registered -> registered.id().equals(pluginId))
                .map(RegisteredPlugin::source).findFirst();
    }

    @Override
    public void start() {
        List<PixivFeaturePlugin> plugins = snapshotPlugins;
        plugins.forEach(PixivFeaturePlugin::start);
        running = true;
        log.info(MessageBundles.get("plugin.log.started", plugins.size(),
                plugins.stream().map(PixivFeaturePlugin::id).collect(Collectors.joining(", "))));
    }

    @Override
    public void stop() {
        List<PixivFeaturePlugin> plugins = snapshotPlugins;
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

    /** 同步更新活动快照及其派生的插件视图（读侧无锁，整体替换引用）。须在 {@link #lock} 内调用（构造期除外，彼时无并发）。 */
    private void setSnapshot(List<RegisteredPlugin> next) {
        this.snapshot = next;
        this.snapshotPlugins = next.stream().map(RegisteredPlugin::plugin)
                .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
    }

    /** 同步更新安装态快照及其派生的插件视图（读侧无锁，整体替换引用）。须在 {@link #lock} 内调用（构造期除外，彼时无并发）。 */
    private void setInstalled(List<RegisteredPlugin> next) {
        this.installed = next;
        this.installedPlugins = next.stream().map(RegisteredPlugin::plugin)
                .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
    }

    /** 是否应进入活动快照：必选插件恒活动，可选插件按 {@code plugins.<id>.enabled} 启用开关。 */
    private boolean isActive(RegisteredPlugin registered) {
        return registered.plugin().required() || toggles.isEnabled(registered.id());
    }

    private static ClassLoader classLoaderOf(PixivFeaturePlugin plugin) {
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        // 极端情形（bootstrap loader 加载的类）回退到系统 classloader，避免下游资源解析拿到 null
        return classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
    }

    private static boolean containsId(List<RegisteredPlugin> plugins, String pluginId) {
        return plugins.stream().anyMatch(registered -> registered.id().equals(pluginId));
    }
}
