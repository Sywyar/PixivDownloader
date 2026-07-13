package top.sywyar.pixivdownload.plugin.registry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PixivPluginDiscoveryBridge;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 * 自动排除禁用插件，其页面 / API / 导航因而不注册。<b>内置核心插件与核心策略声明的必选插件永不可禁用</b>——
 * 即便开关写成 {@code false} 也照常注册。外置插件自己的 {@link PixivFeaturePlugin#required()} 自声明不参与
 * 活动判定，避免第三方插件自封必选后绕过禁用开关。
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
                                   String packageId, long generation, String id) {

        public RegisteredPlugin {
            if (plugin == null) {
                throw new IllegalStateException("registered plugin must not be null");
            }
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("registered plugin id must not be blank");
            }
            if (source == null) {
                throw new IllegalStateException("registered plugin source must not be null (plugin: "
                        + id + ")");
            }
            if (classLoader == null) {
                throw new IllegalStateException("registered plugin classLoader must not be null (plugin: "
                        + id + ")");
            }
            if (packageId == null || packageId.isBlank()) {
                throw new IllegalStateException("registered plugin packageId must not be blank (plugin: "
                        + id + ")");
            }
            if (generation < 0L) {
                throw new IllegalStateException("registered plugin generation must not be negative (plugin: "
                        + id + ")");
            }
            if (source == PluginSource.EXTERNAL && !packageId.equals(id)) {
                throw new IllegalStateException("external plugin packageId must match its stable feature id: "
                        + packageId + " != " + id);
            }
        }

        public RegisteredPlugin(PixivFeaturePlugin plugin, PluginSource source, ClassLoader classLoader) {
            this(capture(plugin), source, classLoader, null, 0L);
        }

        public RegisteredPlugin(PixivFeaturePlugin plugin, PluginSource source, ClassLoader classLoader,
                                String packageId, long generation) {
            this(capture(plugin), source, classLoader, packageId, generation);
        }

        private RegisteredPlugin(PluginIdentitySnapshot identity, PluginSource source, ClassLoader classLoader,
                                 String packageId, long generation) {
            this(identity.plugin(), source, classLoader,
                    packageId == null ? identity.id() : packageId, generation, identity.id());
        }

        private static PluginIdentitySnapshot capture(PixivFeaturePlugin plugin) {
            if (plugin == null) {
                throw new IllegalStateException("registered plugin must not be null");
            }
            return new PluginIdentitySnapshot(plugin, plugin.id());
        }
    }

    private record PluginIdentitySnapshot(PixivFeaturePlugin plugin, String id) {
    }

    private final Object lock = new Object();
    private final Object featureCallbackLock = new Object();
    private final Map<RegisteredPlugin, ActiveIdentityReservationState> activeIdentityReservations =
            new IdentityHashMap<>();
    /** PluginRegistry 与 runtime lifecycle 共享的 feature callback 事实源；对象身份区分代际。 */
    private final Set<RegisteredPlugin> startedFeatures =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** 启用开关：活动成员判定的事实源。构造期与运行期 {@link #register} 都据此决定插件是否进入活动快照。 */
    private final PluginToggleProperties toggles;
    private final RequiredPluginPolicy requiredPluginPolicy;

    /**
     * 全部插件（安装态，内置 + 外置）。构造期建立，运行期 {@link #register} 追加、{@link #unregister} 移除——
     * 注销后该插件不再出现在安装态中（与从未注册过一致）。读路径走不可变快照：变更时整体替换引用（读侧无锁）。
     * schema 合并经此读取，不受启用开关影响（禁用插件仍在安装态，注销插件不在）。
     */
    /** 安装态、活动态及其派生视图一次性发布，读侧永不观察到 register/unregister 的前缀状态。 */
    private volatile RegistryState state = RegistryState.empty();
    private volatile boolean running;

    /** Spring 上下文外（{@code BuiltInPlugins.createAll()}、单元测试）构造：全部插件视为启用、无外置插件。 */
    public PluginRegistry(List<PixivFeaturePlugin> plugins) {
        this(plugins, new PluginToggleProperties(), PluginDiscoveryResult.empty(), RequiredPluginPolicy.empty());
    }

    /** 显式开关 + 无外置插件构造（单元测试用）。 */
    public PluginRegistry(List<PixivFeaturePlugin> plugins, PluginToggleProperties toggles) {
        this(plugins, toggles, PluginDiscoveryResult.empty(), RequiredPluginPolicy.empty());
    }

    /**
     * Spring 构造：内置插件经注入的 {@code List<PixivFeaturePlugin>} 提供，外置插件经发现桥接的
     * {@link PluginDiscoveryResult} 提供（无 {@code plugins/} 目录 / 无外置插件时为空）。按 {@code plugins.<id>.enabled}
     * 决定哪些功能插件进入活动快照（禁用=不注册）。内置核心插件与
     * {@link RequiredPluginPolicy} 声明的必选项永不可禁用。无论启用与否，全部插件都保留在
     * {@link #allPlugins()} 供 schema 合并。
     */
    @Autowired
    public PluginRegistry(List<PixivFeaturePlugin> plugins, PluginToggleProperties toggles,
                          ObjectProvider<PluginDiscoveryResult> externalDiscovery,
                          ObjectProvider<RequiredPluginPolicy> requiredPluginPolicy) {
        this(plugins, toggles, externalDiscovery.getIfAvailable(PluginDiscoveryResult::empty),
                requiredPluginPolicy.getIfAvailable(RequiredPluginPolicy::empty));
    }

    public PluginRegistry(List<PixivFeaturePlugin> plugins, PluginToggleProperties toggles,
                          ObjectProvider<PluginDiscoveryResult> externalDiscovery) {
        this(plugins, toggles, externalDiscovery.getIfAvailable(PluginDiscoveryResult::empty),
                RequiredPluginPolicy.empty());
    }

    /** 全量构造（内置 + 外置发现结果）。Spring 经上面的 {@link ObjectProvider} 构造器走到这里；单元测试也可直接构造双来源注册中心。 */
    public PluginRegistry(List<PixivFeaturePlugin> builtInPlugins, PluginToggleProperties toggles,
                          PluginDiscoveryResult external) {
        this(builtInPlugins, toggles, external, RequiredPluginPolicy.empty());
    }

    public PluginRegistry(List<PixivFeaturePlugin> builtInPlugins, PluginToggleProperties toggles,
                          PluginDiscoveryResult external, RequiredPluginPolicy requiredPluginPolicy) {
        this.toggles = toggles;
        this.requiredPluginPolicy = requiredPluginPolicy != null ? requiredPluginPolicy : RequiredPluginPolicy.empty();
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

        List<RegisteredPlugin> active = new ArrayList<>();
        for (RegisteredPlugin registered : all) {
            if (isActive(registered)) {
                active.add(registered);
            }
        }
        publishState(List.copyOf(all), List.copyOf(active));
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
     * （{@link #allPlugins()}，供 schema 合并 / {@link #disabledPlugins()} 计算），再按启用开关与核心必选策略决定
     * 是否进入活动快照，使 register -> unregister -> register 可逆。
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
            RegistryState current = state;
            if (containsId(current.installed(), pluginId) || containsId(current.active(), pluginId)) {
                throw new IllegalStateException("duplicate plugin id: " + pluginId);
            }
            boolean active = isActive(candidate);
            List<RegisteredPlugin> nextInstalled = new ArrayList<>(current.installed());
            nextInstalled.add(candidate);
            List<RegisteredPlugin> nextActive = new ArrayList<>(current.active());
            if (active) {
                nextActive.add(candidate);
            }
            publishState(List.copyOf(nextInstalled), List.copyOf(nextActive));
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
            RegistryState currentState = state;
            RegisteredPlugin target = findById(currentState.installed(), pluginId);
            if (target == null) {
                target = findById(currentState.active(), pluginId);
            }
            if (target == null) {
                throw new IllegalArgumentException("unknown plugin id: " + pluginId);
            }
            unregisterIdentity(target);
        }
    }

    /**
     * 注销精确注册对象；等待该身份的锁外 reservation 释放后再次复核对象身份，绝不按 id 删除后来接入的新代。
     */
    public void unregister(RegisteredPlugin expected) {
        Objects.requireNonNull(expected, "expected registered plugin");
        synchronized (lock) {
            RegistryState currentState = state;
            RegisteredPlugin current = findById(currentState.installed(), expected.id());
            if (current == null) {
                current = findById(currentState.active(), expected.id());
            }
            if (current != expected) {
                throw new IllegalStateException(
                        "plugin registration is not the expected identity: " + expected.id());
            }
            unregisterIdentity(expected);
        }
    }

    /** 当前安装态或活动快照是否仍包含同一个 RegisteredPlugin 对象。 */
    public boolean containsIdentity(RegisteredPlugin expected) {
        Objects.requireNonNull(expected, "expected registered plugin");
        RegistryState currentState = state;
        return currentState.installed().stream().anyMatch(current -> current == expected)
                || currentState.active().stream().anyMatch(current -> current == expected);
    }

    private void unregisterIdentity(RegisteredPlugin target) {
        String pluginId = target.id();
        awaitIdentityReservationRelease(target);
        RegistryState currentState = state;
        if (!containsIdentity(currentState.installed(), target)
                && !containsIdentity(currentState.active(), target)) {
            throw new IllegalStateException(
                    "plugin registration changed while waiting for active identity reservation: " + pluginId);
        }
        if (startedFeatures.contains(target)) {
            throw new IllegalStateException(
                    "cannot unregister a plugin whose feature callback is still started: " + pluginId);
        }
        List<RegisteredPlugin> nextInstalled = currentState.installed().stream()
                .filter(registered -> registered != target)
                .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        List<RegisteredPlugin> nextActive = currentState.active().stream()
                .filter(registered -> registered != target)
                .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        publishState(nextInstalled, nextActive);
    }

    /**
     * 按注册顺序返回<b>活动</b>（启用）插件的不可变快照。禁用的插件不在其中——下游 registry 经此
     * 聚合，因而自动排除禁用插件的路由 / 导航 / i18n / 静态资源等贡献。需要全部插件（含禁用）
     * 时用 {@link #allPlugins()}（如 schema 合并）；需要来源 / classloader 信息时用 {@link #registeredPlugins()}。
     */
    public List<PixivFeaturePlugin> plugins() {
        return state.activePlugins();
    }

    /** 按注册顺序返回<b>活动</b>插件的源信息快照（插件 + 来源 + 解析用 classloader），不可变。 */
    public List<RegisteredPlugin> registeredPlugins() {
        return state.active();
    }

    /** 当前活动快照是否仍包含同一个注册对象身份。 */
    boolean isActiveIdentity(RegisteredPlugin registered) {
        Objects.requireNonNull(registered, "registered plugin");
        return state.active().stream().anyMatch(current -> current == registered);
    }

    /**
     * 为当前精确活动身份保留一个短期、锁外执行窗口。reservation 建立与身份复核在同一个注册中心锁内完成；
     * 回调执行时不持有该锁，但同一身份的 {@link #unregister(String)} 会等待回调结束。适用于最终提交前存在
     * 不可逆副作用、又不能在注册中心锁内调用插件代码的准备步骤。回调结束（包括异常路径）后 reservation 必定释放。
     */
    public <T> T withActiveIdentityReservation(RegisteredPlugin registered, Supplier<T> action) {
        Objects.requireNonNull(registered, "registered plugin");
        Objects.requireNonNull(action, "action");
        Thread holder = Thread.currentThread();
        ActiveIdentityReservationState reservation;
        synchronized (lock) {
            if (state.active().stream().noneMatch(current -> current == registered)) {
                throw new IllegalStateException(
                        "plugin registration is not the current active identity: " + registered.id());
            }
            reservation = activeIdentityReservations.computeIfAbsent(
                    registered, ignored -> new ActiveIdentityReservationState());
            reservation.acquire(holder);
        }
        try {
            return action.get();
        } finally {
            synchronized (lock) {
                ActiveIdentityReservationState current = activeIdentityReservations.get(registered);
                if (current != reservation || !reservation.release(holder)) {
                    throw new IllegalStateException("invalid plugin active identity reservation state");
                }
                if (reservation.isEmpty()) {
                    activeIdentityReservations.remove(registered);
                    lock.notifyAll();
                }
            }
        }
    }

    /**
     * 在插件注册中心锁内复核精确活动身份并执行一次最终提交。调用方必须先在锁外完成耗时准备；
     * 提交动作不得反向调用插件注册中心的变更方法。跨 registry 的固定锁序是 PluginRegistry → 下游 registry，
     * 因而 unregister / 同 id replacement 不可能插入身份复核与下游提交之间。
     */
    public <T> T commitIfActiveIdentity(RegisteredPlugin registered, Supplier<T> commit) {
        Objects.requireNonNull(registered, "registered plugin");
        Objects.requireNonNull(commit, "commit");
        synchronized (lock) {
            if (state.active().stream().noneMatch(current -> current == registered)) {
                throw new IllegalStateException(
                        "plugin registration is not the current active identity: " + registered.id());
            }
            return commit.get();
        }
    }

    /** 只在 {@link #commitIfActiveIdentity(RegisteredPlugin, Function)} 回调栈内有效的不透明身份提交令牌。 */
    public static final class ActiveIdentityCommit {
        private PluginRegistry authority;
        private RegisteredPlugin registered;
        private boolean active = true;

        private ActiveIdentityCommit(PluginRegistry authority, RegisteredPlugin registered) {
            this.authority = authority;
            this.registered = registered;
        }

        private void deactivate() {
            active = false;
            registered = null;
            authority = null;
        }
    }

    /**
     * 与 Supplier 版语义相同，但向回调提供一个限定在当前锁内栈使用的令牌，供下游避免反向重入
     * PluginRegistry 身份检查。
     */
    public <T> T commitIfActiveIdentity(
            RegisteredPlugin registered,
            Function<ActiveIdentityCommit, T> commit) {
        Objects.requireNonNull(registered, "registered plugin");
        Objects.requireNonNull(commit, "commit");
        synchronized (lock) {
            if (state.active().stream().noneMatch(current -> current == registered)) {
                throw new IllegalStateException(
                        "plugin registration is not the current active identity: " + registered.id());
            }
            ActiveIdentityCommit authority = new ActiveIdentityCommit(this, registered);
            try {
                return commit.apply(authority);
            } finally {
                authority.deactivate();
            }
        }
    }

    void requireActiveIdentityCommit(ActiveIdentityCommit commit, RegisteredPlugin registered) {
        if (commit == null || commit.authority != this || commit.registered != registered
                || !commit.active || !Thread.holdsLock(lock)) {
            throw new IllegalStateException("invalid plugin active identity commit authority");
        }
    }

    private void awaitIdentityReservationRelease(RegisteredPlugin registered) {
        boolean interrupted = false;
        try {
            ActiveIdentityReservationState reservation = activeIdentityReservations.get(registered);
            while (reservation != null) {
                if (reservation.isHeldBy(Thread.currentThread())) {
                    throw new IllegalStateException(
                            "cannot unregister plugin from its active identity reservation: " + registered.id());
                }
                try {
                    lock.wait();
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
                reservation = activeIdentityReservations.get(registered);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class ActiveIdentityReservationState {
        private final Map<Thread, Integer> holders = new IdentityHashMap<>();
        private int count;

        private void acquire(Thread holder) {
            holders.merge(holder, 1, Math::addExact);
            count = Math.incrementExact(count);
        }

        private boolean release(Thread holder) {
            Integer held = holders.get(holder);
            if (held == null || held < 1 || count < 1) {
                return false;
            }
            if (held == 1) {
                holders.remove(holder);
            } else {
                holders.put(holder, held - 1);
            }
            count--;
            return true;
        }

        private boolean isHeldBy(Thread thread) {
            return holders.containsKey(thread);
        }

        private boolean isEmpty() {
            return count == 0;
        }
    }

    /**
     * 返回全部插件（安装态，内置 + 外置，含被禁用的），不受启用开关影响。安装态在构造期建立、随
     * {@link #register} / {@link #unregister} 可逆增删（禁用插件仍在，注销插件不在）。供必须覆盖全部已安装插件
     * 的场景使用——典型是 schema 合并（禁用插件的表 / 列仍需创建、数据保留；注销插件的 schema 不再合并）。
     */
    public List<PixivFeaturePlugin> allPlugins() {
        return state.installedPlugins();
    }

    /** 全部安装态插件的稳定身份记录；下游建立 owner key 时不得再次调用可变的 {@code plugin.id()}。 */
    public List<RegisteredPlugin> allRegisteredPlugins() {
        return state.installed();
    }

    /** 返回被禁用（安装但未进入活动快照）的插件。供维护任务按归属跳过禁用插件的任务等场景使用。 */
    public List<PixivFeaturePlugin> disabledPlugins() {
        RegistryState currentState = state;
        List<RegisteredPlugin> activeNow = currentState.active();
        List<RegisteredPlugin> installedNow = currentState.installed();
        java.util.Set<String> activeIds = activeNow.stream()
                .map(RegisteredPlugin::id)
                .collect(Collectors.toSet());
        return installedNow.stream()
                .filter(registered -> !activeIds.contains(registered.id()))
                .map(RegisteredPlugin::plugin)
                .toList();
    }

    public Optional<PixivFeaturePlugin> find(String pluginId) {
        return state.active().stream().filter(registered -> registered.id().equals(pluginId))
                .map(RegisteredPlugin::plugin).findFirst();
    }

    /** 活动插件的来源（{@link PluginSource}），未注册（或已禁用）时为空。 */
    public Optional<PluginSource> source(String pluginId) {
        return state.active().stream().filter(registered -> registered.id().equals(pluginId))
                .map(RegisteredPlugin::source).findFirst();
    }

    @Override
    public void start() {
        List<RegisteredPlugin> plugins = state.active();
        List<RegisteredPlugin> startedThisAttempt = new ArrayList<>();
        Throwable startFailure = null;
        for (RegisteredPlugin registered : plugins) {
            try {
                if (startFeature(registered)) {
                    startedThisAttempt.add(registered);
                }
            } catch (Throwable failure) {
                startFailure = failure;
                break;
            }
        }
        if (startFailure != null) {
            Throwable cleanupFatal = null;
            for (int i = startedThisAttempt.size() - 1; i >= 0; i--) {
                try {
                    stopFeature(startedThisAttempt.get(i));
                } catch (Throwable cleanupFailure) {
                    if (!isFatal(startFailure) && isFatal(cleanupFailure)) {
                        if (cleanupFatal == null) {
                            cleanupFatal = cleanupFailure;
                            addSuppressedSafely(cleanupFatal, startFailure);
                        } else {
                            addSuppressedSafely(cleanupFatal, cleanupFailure);
                        }
                    } else {
                        addSuppressedSafely(cleanupFatal != null ? cleanupFatal : startFailure, cleanupFailure);
                    }
                }
            }
            running = false;
            rethrowUnchecked(cleanupFatal != null ? cleanupFatal : startFailure);
        }
        running = true;
        log.info(MessageBundles.get("plugin.log.started", plugins.size(),
                plugins.stream().map(RegisteredPlugin::id).collect(Collectors.joining(", "))));
    }

    @Override
    public void stop() {
        List<RegisteredPlugin> plugins = state.active();
        Throwable fatal = null;
        for (int i = plugins.size() - 1; i >= 0; i--) {
            RegisteredPlugin registered = plugins.get(i);
            try {
                stopFeature(registered);
            } catch (Throwable failure) {
                if (isFatal(failure)) {
                    if (fatal == null) {
                        fatal = failure;
                    } else {
                        addSuppressedSafely(fatal, failure);
                    }
                } else {
                    if (fatal != null) {
                        addSuppressedSafely(fatal, failure);
                    }
                    log.warn(MessageBundles.get(
                            "plugin.log.stop-failed", registered.id(), failure.getMessage()), failure);
                }
            }
        }
        running = false;
        if (fatal != null) {
            rethrowFatal(fatal);
        }
    }

    /**
     * 对精确活动身份幂等调用 feature start；成功返回后才记录 started。runtime restart 与 SmartLifecycle 共用此入口。
     */
    public boolean startFeature(RegisteredPlugin registered) {
        Objects.requireNonNull(registered, "registered plugin");
        synchronized (featureCallbackLock) {
            synchronized (lock) {
                if (startedFeatures.contains(registered)) {
                    return false;
                }
            }
            return withActiveIdentityReservation(registered, () -> {
                registered.plugin().start();
                synchronized (lock) {
                    startedFeatures.add(registered);
                }
                return true;
            });
        }
    }

    /**
     * 对精确活动身份幂等调用 feature stop；只有回调成功才移除 started 标记，失败保留以供 runtime / shutdown 重试。
     */
    public boolean stopFeature(RegisteredPlugin registered) {
        Objects.requireNonNull(registered, "registered plugin");
        synchronized (featureCallbackLock) {
            synchronized (lock) {
                if (!startedFeatures.contains(registered)) {
                    return false;
                }
            }
            return withActiveIdentityReservation(registered, () -> {
                registered.plugin().stop();
                synchronized (lock) {
                    startedFeatures.remove(registered);
                }
                return true;
            });
        }
    }

    /** 当前精确身份的 feature start 回调是否已成功且尚未成功 stop。 */
    public boolean featureStarted(RegisteredPlugin registered) {
        Objects.requireNonNull(registered, "registered plugin");
        synchronized (lock) {
            return startedFeatures.contains(registered);
        }
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void rethrowUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("feature lifecycle callback failed", failure);
    }

    private static void addSuppressedSafely(Throwable target, Throwable suppressed) {
        if (target == suppressed) {
            return;
        }
        try {
            target.addSuppressed(suppressed);
        } catch (Throwable ignored) {
            // 诊断附加失败不得覆盖首个 start 失败。
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 先启动插件回调、后于外置服务足迹停止。 */
    @Override
    public int getPhase() {
        return 0;
    }

    /** 完整准备派生视图后通过单个 volatile 引用一次性发布安装态与活动态。 */
    private void publishState(List<RegisteredPlugin> installed, List<RegisteredPlugin> active) {
        List<PixivFeaturePlugin> installedPlugins = installed.stream().map(RegisteredPlugin::plugin)
                .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        List<PixivFeaturePlugin> activePlugins = active.stream().map(RegisteredPlugin::plugin)
                .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        state = new RegistryState(installed, installedPlugins, active, activePlugins);
    }

    private record RegistryState(
            List<RegisteredPlugin> installed,
            List<PixivFeaturePlugin> installedPlugins,
            List<RegisteredPlugin> active,
            List<PixivFeaturePlugin> activePlugins) {

        private static RegistryState empty() {
            return new RegistryState(List.of(), List.of(), List.of(), List.of());
        }
    }

    /** 是否应进入活动快照：内置核心与核心策略必选项恒活动，其它插件按启用开关。 */
    private boolean isActive(RegisteredPlugin registered) {
        return isBuiltInCore(registered) || requiredPluginPolicy.isRequired(registered.id())
                || toggles.isEnabled(registered.id());
    }

    private static boolean isBuiltInCore(RegisteredPlugin registered) {
        return registered.source() == PluginSource.BUILT_IN && registered.plugin().kind() == PluginKind.CORE;
    }

    private static ClassLoader classLoaderOf(PixivFeaturePlugin plugin) {
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        // 极端情形（bootstrap loader 加载的类）回退到系统 classloader，避免下游资源解析拿到 null
        return classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
    }

    private static boolean containsId(List<RegisteredPlugin> plugins, String pluginId) {
        return plugins.stream().anyMatch(registered -> registered.id().equals(pluginId));
    }

    private static boolean containsIdentity(
            List<RegisteredPlugin> plugins, RegisteredPlugin expected) {
        return plugins.stream().anyMatch(registered -> registered == expected);
    }

    private static RegisteredPlugin findById(
            List<RegisteredPlugin> plugins, String pluginId) {
        return plugins.stream()
                .filter(registered -> registered.id().equals(pluginId))
                .findFirst()
                .orElse(null);
    }
}
