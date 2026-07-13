package top.sywyar.pixivdownload.plugin.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.lifecycle.quiesce.PluginRuntimeTaskQuiescer;
import top.sywyar.pixivdownload.plugin.management.PluginManagementErrorCode;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.LoadedPluginPackage;
import top.sywyar.pixivdownload.plugin.web.PluginControllerRegistrar;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionRegistrar;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 外置插件运行期热启停 / quiesce 生命周期服务：把单个外置插件的 {@code load} / {@code start} / {@code quiesce} /
 * {@code stop} / {@code unload} / {@code reload} 收口为核心内部 API，按既定顺序编排各注册中心——子 context 工厂
 * （{@link PluginApplicationContextFactory}）、controller 注册器（{@link PluginControllerRegistrar}）、web 贡献
 * 注册器（{@link PluginWebContributionRegistrar}）、schedule 贡献注册器（{@link PluginScheduleContributionRegistrar}）、
 * 运行期任务清退器（{@link PluginRuntimeTaskQuiescer}）、核心 {@link PluginRegistry}——并驱动状态机
 * {@link PluginLifecycleState}。{@link ExternalPluginContextManager}
 * （{@code SmartLifecycle}）在核心 context 刷新 / 关闭时调 {@link #startAll()} / {@link #stopAll()} 驱动启动期接入与关闭期收尾。
 *
 * <h2>停止顺序（与启动期接入对称）</h2>
 * {@code stop} 依次：<b>① 标记 {@link PluginRuntimePhase#QUIESCED}</b>（请求网关随即拒绝命中其路由的新请求）→
 * <b>② 精确撤回本 generation 的 schedule publication</b>（统一 registry 原子拒绝新 lease，并向旧 lease 发取消）→
 * <b>③ 关闭 SSE、排空在途下载队列</b> → <b>④ 等待旧 generation lease 归零</b> → <b>⑤ 拆除服务足迹</b>
 * （{@link #tearDownServing}：注销 controller / runtime capability / web 贡献 → 调插件 {@code stop()} → 关闭子 context）。
 * schedule 撤回失败不会被吞掉；运行期等待超时返回 {@link PluginManagementErrorCode#OPERATION_IN_PROGRESS} 并保持
 * {@link PluginRuntimePhase#QUIESCED}，绝不关闭仍被 lease 引用的 child context。其余足迹拆除继续沿用 best-effort 隔离。
 *
 * <h2>插件自身 {@code start()} / {@code stop()}</h2>
 * 插件自身的生命周期回调与服务足迹（web / controller / 子 context）分属不同时机：<b>启动期</b>全部活动插件的
 * {@code start()} 由 {@link PluginRegistry}（{@code SmartLifecycle}）统一调用一次，故 {@link #startAll()} 只建立服务
 * 足迹、<b>不重复调 {@code start()}</b>（启动期接入路径 {@link #bringUpFromBoot} 传 {@code invokePluginStart=false}）；
 * <b>运行期</b>则由本服务负责——{@link #stop(String)} 调插件 {@code stop()}、{@link #start(String)}（及
 * {@link #reload(String)} 的重启段）调插件 {@code start()}，使 {@code start → stop → start} 既重建服务足迹也恢复插件
 * 自身生命周期（与 {@code stop} 调 {@code stop()} 对称）。运行期 {@code start()} 在足迹建立后调用、若抛异常即回滚本次
 * 已建立的足迹（controller / web / 子 context）并落 {@link PluginRuntimePhase#STOPPED}、不进入 STARTED。
 *
 * <h2>幂等与可重复</h2>
 * 重复 {@code stop} / {@code unload} 不破坏状态（已停 / 已卸即静默返回）；{@code start → stop → start} 可重复
 * （{@code stop} 注销 web，{@code start} 重新接入 web + controller + 重建子 context）。{@code load} / {@code unload}
 * 在<b>核心注册中心层面</b>对称增删（{@link PluginRegistry#register} / {@link PluginRegistry#unregister}，与启动期
 * 接入语义一致、可逆）；PF4J classloader 的物理装卸与回收不在本服务范围。
 *
 * <h2>运行期任务清退</h2>
 * {@code quiesce} 与 {@code stop} 在标记 {@link PluginRuntimePhase#QUIESCED} 后、拆除服务足迹前，先<b>停住该插件的
 * 新计划任务派发</b>、再清退其在途运行期资源（新 HTTP 请求已由 {@link PluginQuiesceGate} 在 HTTP 层 503 挡住）。
 * 具体顺序由 {@link PluginRuntimeTaskQuiescer} 内聚负责：
 * <ol>
 *   <li><b>停新计划任务派发</b>——经
 *       {@link PluginScheduleContributionRegistrar#withdraw} 用精确 publication token 从统一
 *       {@code ScheduleCapabilityRegistry} 撤回 owner bundle；旧 generation 的 planning / execution lease 同时收到协作式
 *       取消，新 planning lease 不再获取来源、执行器、凭据策略或 guard。残留任务数据保留，待能力恢复后可重新解析。</li>
 *   <li><b>清退在途资源</b>——① 经 {@link PluginStreamRegistry#closeForPlugin} 关闭该插件全部活动 SSE 推流并向客户端
 *       发「插件不可用」事件；② 据插件 {@link PixivFeaturePlugin#queueTypes()} 声明的每个 queueType 经核心队列宿主注册中心
 *       {@code QueueOperationRegistry} 解析操作适配器并
 *       {@link top.sywyar.pixivdownload.core.download.queue.QueueOperations#clearAll() clearAll} 排空 / 取消其在途下载任务
 *       （不直依赖任一具体下载实现）。</li>
 * </ol>
 * schedule 撤回属于安全前置条件，失败向上抛出；SSE 与队列步骤彼此隔离。某 queueType 操作缺席（贡献它的插件已禁 / 卸）
 * 时跳过、不报错。在途下载经
 * 协作式取消（{@code DownloadStatus} 标志位）停止——长任务据此干净退出。异步任务一律走核心受管执行器，本服务不新建线程池。
 *
 * <p>本服务把外置插件标识为单一 {@code pluginId}：对全部在场外置插件，其功能插件 id 与 PF4J 包 id 重合，故子
 * context / controller / web 贡献 / 路由声明 / 状态机统一以该 id 为键。所有变更在本对象锁内串行；写入各注册中心
 * 复用其内部锁。无外置插件时本服务为空、透明无副作用。本服务不触碰鉴权（{@code AuthFilter} 按
 * {@link RouteAccessRegistry} 独立执行）。
 */
@Slf4j
@Component
public class PluginLifecycleService {

    private static final long RUNTIME_SCHEDULE_DRAIN_TIMEOUT_NANOS = 30_000_000_000L;

    private final ApplicationContext parent;
    private final PluginRuntimeManager pluginRuntimeManager;
    private final PluginApplicationContextFactory contextFactory;
    private final PluginControllerRegistrar controllerRegistrar;
    private final PluginWebContributionRegistrar webContributionRegistrar;
    private final PluginScheduleContributionRegistrar scheduleContributionRegistrar;
    private final PluginRuntimeTaskQuiescer runtimeTaskQuiescer;
    private final PluginCapabilityContributionRegistrar capabilityContributionRegistrar;
    private final PluginRegistry pluginRegistry;
    private final PluginLifecycleState lifecycleState;
    private final ScheduleContributionLifecycleAuthority scheduleMutationAuthority =
            new ScheduleContributionLifecycleAuthority();

    private final Object lock = new Object();
    /** 按接入顺序保存的受管外置插件（键 = pluginId）。仅在 {@link #lock} 内变更，读路径取只读视图。 */
    private final Map<String, ManagedPlugin> managed = new LinkedHashMap<>();
    private volatile boolean started;

    public PluginLifecycleService(ApplicationContext parent,
                                  PluginRuntimeManager pluginRuntimeManager,
                                  PluginApplicationContextFactory contextFactory,
                                  PluginControllerRegistrar controllerRegistrar,
                                  PluginWebContributionRegistrar webContributionRegistrar,
                                  PluginScheduleContributionRegistrar scheduleContributionRegistrar,
                                  PluginRuntimeTaskQuiescer runtimeTaskQuiescer,
                                  PluginCapabilityContributionRegistrar capabilityContributionRegistrar,
                                  PluginRegistry pluginRegistry,
                                  PluginLifecycleState lifecycleState) {
        this.parent = parent;
        this.pluginRuntimeManager = pluginRuntimeManager;
        this.contextFactory = contextFactory;
        this.controllerRegistrar = controllerRegistrar;
        this.webContributionRegistrar = webContributionRegistrar;
        this.scheduleContributionRegistrar = scheduleContributionRegistrar;
        this.runtimeTaskQuiescer = runtimeTaskQuiescer;
        this.capabilityContributionRegistrar = capabilityContributionRegistrar;
        this.pluginRegistry = pluginRegistry;
        this.lifecycleState = lifecycleState;
    }

    // ---- 启动期接入 / 关闭期收尾（由 ExternalPluginContextManager 驱动）----

    /**
     * 启动期为全部已发现的外置插件建立服务足迹。声明了配置类的插件包建子 context 并动态注册其 controller；
     * web 贡献（route / static / i18n / navigation / userscript）已在各下游注册中心<b>构造期</b>从
     * {@link PluginRegistry} 活动快照接入，本方法不重复接入。单个插件接入失败被隔离、不致核心壳启动失败。
     */
    public void startAll() {
        synchronized (lock) {
            if (started) {
                return;
            }
            List<PluginRegistry.RegisteredPlugin> external = externalRegisteredPlugins();
            // 1) 有子 context 装配定义的外置插件包（来自发现桥接清点）：建子 context + 注册 controller。
            for (PluginContextModule module : pluginRuntimeManager.inspectContextModules()) {
                String pluginId = module.sourcePluginId();
                if (managed.containsKey(pluginId)) {
                    log.warn("Duplicate plugin context module for '{}' - keeping the first, skipping.", pluginId);
                    continue;
                }
                ManagedPlugin record = new ManagedPlugin(pluginId, module, findRegistered(external, pluginId));
                managed.put(pluginId, record);
                bringUpFromBoot(record);
            }
            // 2) 没有子 context 的纯贡献外置插件：仍需在最终 bring-up 点发布其纯元数据 schedule 能力。
            for (PluginRegistry.RegisteredPlugin rp : external) {
                if (managed.containsKey(rp.id())) {
                    continue;
                }
                ManagedPlugin record = new ManagedPlugin(rp.id(), null, rp);
                managed.put(rp.id(), record);
                bringUpFromBoot(record);
            }
            started = true;
        }
        log.info("External plugin lifecycle started: {} plugin(s) {}.", managed.size(), managed.keySet());
    }

    /**
     * 关闭期逆序停止全部仍在服务的外置插件（拆除服务足迹）。不从核心注册中心卸下——整壳正在关闭，
     * 与启动期接入对称的关闭收尾即可。对未在服务（已停 / 已卸 / 纯加载）的插件跳过。
     */
    public void stopAll() {
        synchronized (lock) {
            if (!started) {
                return;
            }
            List<String> ids = new ArrayList<>(managed.keySet());
            Collections.reverse(ids);
            // 先对全部插件撤回 publication 并发出取消，避免逐插件等待时其它 owner 仍继续接收新执行。
            for (String id : ids) {
                ManagedPlugin record = managed.get(id);
                PluginRuntimePhase phase = lifecycleState.phase(id).orElse(null);
                if (phase == PluginRuntimePhase.STARTED || phase == PluginRuntimePhase.QUIESCED) {
                    ensureQuiesced(record);
                }
            }

            // 核心 context 关闭不能在 lease 仍活动时继续销毁父服务；忽略中断直到所有旧代执行真实归零。
            boolean interrupted = false;
            for (String id : ids) {
                ManagedPlugin record = managed.get(id);
                PluginRuntimePhase phase = lifecycleState.phase(id).orElse(null);
                if (phase == PluginRuntimePhase.QUIESCED) {
                    interrupted |= awaitDrainUninterruptibly(record);
                    finishStop(record);
                }
            }
            started = false;
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---- 运行期热启停 / quiesce API（按 pluginId）----

    /** 启动（重新建立服务足迹）一个当前已停止 / 已加载的外置插件。已在服务则幂等返回。 */
    public void start(String pluginId) {
        synchronized (lock) {
            doStart(require(pluginId));
        }
    }

    /**
     * 静默一个正在服务的外置插件：标记 {@link PluginRuntimePhase#QUIESCED}，命中其路由的新 HTTP 请求随即被拒绝、
     * 其 schedule 贡献被注销（新计划任务派发即 {@code SOURCE_UNAVAILABLE} 干净挂起、数据不删），并清退其在途 SSE /
     * 下载任务。已静默则幂等返回。
     */
    public void quiesce(String pluginId) {
        synchronized (lock) {
            doQuiesce(require(pluginId));
        }
    }

    /** 停止一个外置插件：quiesce 标记后按序拆除服务足迹。已停 / 已卸 / 纯加载则幂等返回。 */
    public void stop(String pluginId) {
        synchronized (lock) {
            doStop(require(pluginId));
        }
    }

    /** 卸下一个外置插件：先确保停止，再从核心注册中心移除（与接入对称、可逆）。已卸则幂等返回。 */
    public void unload(String pluginId) {
        synchronized (lock) {
            doUnload(require(pluginId));
        }
    }

    /** 重新接入一个已卸下的外置插件到核心注册中心（可逆于 {@link #unload}）。已加载则幂等返回。 */
    public void load(String pluginId) {
        synchronized (lock) {
            doLoad(require(pluginId));
        }
    }

    /** 重载一个外置插件 = stop 后再 start（回收并重建服务足迹）。已卸下的插件不能重载（需先 load）。 */
    public void reload(String pluginId) {
        synchronized (lock) {
            ManagedPlugin record = require(pluginId);
            PluginRuntimePhase phase = lifecycleState.phase(pluginId).orElse(null);
            if (phase == PluginRuntimePhase.UNLOADED) {
                throw new PluginLifecycleException("cannot reload an unloaded plugin '" + pluginId
                        + "'; load it first");
            }
            if (phase == PluginRuntimePhase.STARTED || phase == PluginRuntimePhase.QUIESCED) {
                doStop(record);
            }
            doStart(record);
        }
    }

    /** 不更换 classloader 的服务重启。 */
    public void restart(String pluginId) {
        reload(pluginId);
    }

    /**
     * 接入一个刚由 PF4J 物理加载的新 generation。当前发行格式显式要求一包一个同 id 功能插件；
     * 违反约束时拒绝接入，而不是把包身份和功能身份混用后继续运行。
     */
    public void adoptLoadedPackage(LoadedPluginPackage loaded) {
        synchronized (lock) {
            String packageId = loaded.packageId();
            if (managed.containsKey(packageId)) {
                throw new PluginLifecycleException("plugin package is already managed: " + packageId);
            }
            List<PluginInstallation> registrable = loaded.inventory().installations().stream()
                    .filter(PluginInstallation::registrable).toList();
            if (registrable.size() != 1 || !packageId.equals(registrable.get(0).id())) {
                throw new PluginLifecycleException("external package '" + packageId
                        + "' must contribute exactly one feature plugin with the same id");
            }
            if (loaded.contextModules().size() > 1) {
                throw new PluginLifecycleException("external package '" + packageId
                        + "' declared multiple context modules");
            }
            PluginInstallation installation = registrable.get(0);
            PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                    installation.plugin(), PluginSource.EXTERNAL, installation.classLoader(),
                    packageId, loaded.generation());
            try {
                pluginRegistry.register(registered);
            } catch (RuntimeException e) {
                throw new PluginLifecycleException("failed to register new generation for '" + packageId + "'", e);
            }
            PluginContextModule module = loaded.contextModules().isEmpty() ? null : loaded.contextModules().get(0);
            managed.put(packageId, new ManagedPlugin(packageId, loaded.generation(), module, registered));
            lifecycleState.initialize(packageId, PluginRuntimePhase.LOADED);
        }
    }

    /** 物理卸载成功后删除本代全部强引用；仅留下 PluginLifecycleState 中的纯值 UNLOADED 状态。 */
    public void forgetUnloadedGeneration(String packageId, long generation) {
        synchronized (lock) {
            ManagedPlugin record = require(packageId);
            if (record.generation != generation) {
                throw new PluginLifecycleException("stale generation cleanup for '" + packageId + "': expected "
                        + record.generation + ", got " + generation);
            }
            if (lifecycleState.phase(packageId).orElse(null) != PluginRuntimePhase.UNLOADED) {
                throw new PluginLifecycleException("cannot forget a generation that is not unloaded: " + packageId);
            }
            managed.remove(packageId);
        }
    }

    public Optional<Long> generation(String packageId) {
        synchronized (lock) {
            ManagedPlugin record = managed.get(packageId);
            return record == null ? Optional.empty() : Optional.of(record.generation);
        }
    }

    /** 当前运行时已加载外置插件的 artifact 路径快照。 */
    public Optional<Path> artifactPath(String packageId) {
        return pluginRuntimeManager.artifactPath(packageId);
    }

    /** 当前已加载 generation 的纯值包描述符；STOPPED 时仍在，物理卸载后消失。 */
    public Optional<PluginDescriptor> descriptor(String packageId) {
        return pluginRuntimeManager.loadedDescriptor(packageId);
    }

    /** 磁盘包删除后移除纯值生命周期记录。 */
    public void forgetInstallation(String packageId) {
        synchronized (lock) {
            if (managed.containsKey(packageId)) {
                throw new PluginLifecycleException("cannot forget a loaded installation: " + packageId);
            }
            lifecycleState.remove(packageId);
        }
    }

    // ---- 观测 ----

    /** 指定外置插件当前的子 context（未建立 / 已关闭时为空）。 */
    public Optional<ConfigurableApplicationContext> contextFor(String pluginId) {
        synchronized (lock) {
            ManagedPlugin record = managed.get(pluginId);
            return record == null ? Optional.empty() : Optional.ofNullable(record.context);
        }
    }

    /** 当前持有活动子 context 的外置插件 id（按接入顺序，只读快照）。 */
    public Set<String> servingPluginIds() {
        synchronized (lock) {
            Set<String> ids = new LinkedHashSet<>();
            for (ManagedPlugin record : managed.values()) {
                if (record.context != null) {
                    ids.add(record.pluginId);
                }
            }
            return Collections.unmodifiableSet(ids);
        }
    }

    /** 当前持有的活动子 context 数量。 */
    public int contextCount() {
        synchronized (lock) {
            int count = 0;
            for (ManagedPlugin record : managed.values()) {
                if (record.context != null) {
                    count++;
                }
            }
            return count;
        }
    }

    /** 全部受管外置插件 id（含已停 / 已卸，按接入顺序，只读快照）。 */
    public Set<String> managedPluginIds() {
        synchronized (lock) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(managed.keySet()));
        }
    }

    /** 指定外置插件当前阶段（未受管时为空）。 */
    public Optional<PluginRuntimePhase> phase(String pluginId) {
        return lifecycleState.phase(pluginId);
    }

    // ---- 内部：阶段驱动（均在 lock 内调用）----

    private void doStart(ManagedPlugin record) {
        PluginRuntimePhase phase = lifecycleState.phase(record.pluginId).orElse(null);
        if (phase == PluginRuntimePhase.STARTED) {
            return; // 幂等：已在服务
        }
        if (phase != PluginRuntimePhase.STOPPED && phase != PluginRuntimePhase.LOADED) {
            throw new PluginLifecycleException("cannot start plugin '" + record.pluginId + "' from phase " + phase);
        }
        bringUpServing(record, /* invokePluginStart */ true); // 运行期 start / reload：重新调插件 start() 恢复其生命周期
    }

    private void doQuiesce(ManagedPlugin record) {
        PluginRuntimePhase phase = lifecycleState.phase(record.pluginId).orElse(null);
        if (phase != PluginRuntimePhase.STARTED && phase != PluginRuntimePhase.QUIESCED) {
            throw new PluginLifecycleException("cannot quiesce plugin '" + record.pluginId + "' from phase " + phase);
        }
        ensureQuiesced(record);
        log.info("Quiesced plugin '{}': no longer accepting new requests.", record.pluginId);
    }

    private void doStop(ManagedPlugin record) {
        String pluginId = record.pluginId;
        PluginRuntimePhase phase = lifecycleState.phase(pluginId).orElse(null);
        if (phase == PluginRuntimePhase.STOPPED || phase == PluginRuntimePhase.UNLOADED
                || phase == PluginRuntimePhase.LOADED) {
            return; // 幂等 / 无服务足迹可拆
        }
        ensureQuiesced(record);
        long deadline = System.nanoTime() + RUNTIME_SCHEDULE_DRAIN_TIMEOUT_NANOS;
        ScheduleGenerationDrain drain = record.scheduleDrain;
        if (drain != null && !drain.awaitDrained(deadline)) {
            throw new ClassifiedPluginLifecycleException(
                    PluginManagementErrorCode.OPERATION_IN_PROGRESS,
                    "plugin '" + pluginId + "' remains quiesced with "
                            + drain.activeLeaseCount() + " active schedule lease(s)");
        }
        finishStop(record);
    }

    /** 标记 QUIESCED，并且恰好一次按 publication → SSE → queue 的顺序发起运行期清退。 */
    private void ensureQuiesced(ManagedPlugin record) {
        PluginRuntimePhase phase = lifecycleState.phase(record.pluginId).orElse(null);
        if (phase == PluginRuntimePhase.STARTED) {
            lifecycleState.transition(record.pluginId, PluginRuntimePhase.QUIESCED);
        } else if (phase != PluginRuntimePhase.QUIESCED) {
            throw new PluginLifecycleException("cannot quiesce plugin '" + record.pluginId
                    + "' from phase " + phase);
        }
        if (record.runtimeTasksQuiesced) {
            return;
        }
        PluginRuntimeTaskQuiescer.QuiesceResult result = runtimeTaskQuiescer.quiesce(
                scheduleMutationAuthority,
                record.pluginId, record.schedulePublication, record.plugin());
        record.scheduleDrain = result.scheduleDrain().orElse(null);
        record.runtimeTasksQuiesced = true;
    }

    /** lease 已归零后的唯一服务足迹关闭入口。 */
    private void finishStop(ManagedPlugin record) {
        tearDownServing(record);
        record.schedulePublication = null;
        record.scheduleDrain = null;
        record.runtimeTasksQuiesced = false;
        lifecycleState.set(record.pluginId, PluginRuntimePhase.STOPPED);
        log.info("Stopped plugin '{}'.", record.pluginId);
    }

    /** clean context shutdown 使用：清除中断并持续等待，返回是否曾观察到中断。 */
    private static boolean awaitDrainUninterruptibly(ManagedPlugin record) {
        ScheduleGenerationDrain drain = record.scheduleDrain;
        boolean interrupted = false;
        while (drain != null && !drain.isDrained()) {
            if (!drain.awaitDrained()) {
                interrupted |= Thread.interrupted();
            }
        }
        return interrupted;
    }

    private void doUnload(ManagedPlugin record) {
        String pluginId = record.pluginId;
        PluginRuntimePhase phase = lifecycleState.phase(pluginId).orElse(null);
        if (phase == PluginRuntimePhase.UNLOADED) {
            return; // 幂等
        }
        if (phase == PluginRuntimePhase.STARTED || phase == PluginRuntimePhase.QUIESCED) {
            doStop(record); // 先拆服务足迹 → STOPPED
        }
        // 从核心注册中心移除（与启动期接入对称、可逆）；注销后下游 registry 重建不再合并其 schema / 贡献。
        if (record.registered != null) {
            try {
                pluginRegistry.unregister(pluginId);
            } catch (RuntimeException e) {
                log.warn("Error unregistering plugin '{}' from core registry: {}", pluginId, e.toString());
            }
        }
        lifecycleState.transition(pluginId, PluginRuntimePhase.UNLOADED);
        log.info("Unloaded plugin '{}' from core registry.", pluginId);
    }

    private void doLoad(ManagedPlugin record) {
        String pluginId = record.pluginId;
        PluginRuntimePhase phase = lifecycleState.phase(pluginId).orElse(null);
        if (phase == PluginRuntimePhase.LOADED) {
            return; // 幂等
        }
        if (phase != PluginRuntimePhase.UNLOADED) {
            throw new PluginLifecycleException("cannot load plugin '" + pluginId + "' from phase " + phase);
        }
        if (record.registered != null) {
            try {
                pluginRegistry.register(record.registered);
            } catch (RuntimeException e) {
                throw new PluginLifecycleException("failed to re-register plugin '" + pluginId
                        + "' into core registry", e);
            }
        }
        lifecycleState.transition(pluginId, PluginRuntimePhase.LOADED);
        log.info("Loaded plugin '{}' into core registry.", pluginId);
    }

    // ---- 内部：服务足迹建立 / 拆除 ----

    /**
     * 启动期接入：阶段从 LOADED 起步，web 贡献已在各注册中心构造期接入故不重复接入；插件自身 {@code start()} 已由
     * {@link PluginRegistry}（{@code SmartLifecycle}）在启动期统一调用，故本路径<b>不重复调 {@code start()}</b>
     * （区别于运行期 {@link #doStart}）。
     */
    private void bringUpFromBoot(ManagedPlugin record) {
        record.webRegistered = record.registered != null; // 构造期已接入其（非空）web 贡献
        lifecycleState.initialize(record.pluginId, PluginRuntimePhase.LOADED);
        bringUpServing(record, /* invokePluginStart */ false); // 启动期 start() 归 PluginRegistry，本服务不重复
    }

    /**
     * 建立服务足迹：（按需）接入 web 贡献 → 建子 context → 接入 runtime capability → 动态注册 controller →
     *（仅运行期重启）调插件 {@code start()} → 最终原子发布 schedule owner bundle，成功后阶段落到
     * {@link PluginRuntimePhase#STARTED}。任一步失败被隔离
     *（回滚本次已建立的足迹、阶段落到 STOPPED），不向上抛、不影响其它插件。
     *
     * <p>schedule 行为 Bean 依赖 child context，但 publication 必须是 bring-up 的最后一个可失败步骤：这样 capability、
     * controller 或插件 {@code start()} 失败时统一 registry 从未暴露半可用 owner，也不存在回滚关闭已被租用 Bean 的窗口。
     *
     * @param invokePluginStart 足迹建立后是否调用插件自身 {@code start()}。<b>启动期接入（boot）传 {@code false}</b>——
     *        全部活动插件的 {@code start()} 由 {@link PluginRegistry}（{@code SmartLifecycle}）在启动期统一调用一次,
     *        本服务不重复；<b>运行期 start / reload 传 {@code true}</b>——插件已被 {@code stop()} 过，需重新调其
     *        {@code start()} 恢复插件自身生命周期。运行期 {@code start()} 抛异常即回滚本次足迹（见 {@link #rollBackBringUp}）。
     */
    private void bringUpServing(ManagedPlugin record, boolean invokePluginStart) {
        String pluginId = record.pluginId;
        // a) web 贡献：仅当当前未接入且有注册条目时接入（运行期重启路径在此重新接入；启动期构造期已接入故跳过）。
        if (!record.webRegistered && record.registered != null) {
            try {
                webContributionRegistrar.register(record.registered);
                record.webRegistered = true;
            } catch (RuntimeException e) {
                log.error("Failed to register web contributions for plugin '{}': {} - isolating.",
                        pluginId, e.toString(), e);
                lifecycleState.set(pluginId, PluginRuntimePhase.STOPPED);
                return;
            }
        }
        // b) 子 context（仅声明了配置类的插件包）。
        if (record.module != null) {
            ConfigurableApplicationContext child;
            try {
                child = contextFactory.create(parent, record.module);
            } catch (Exception e) {
                log.error("Failed to start child context for plugin '{}': {} - isolating.",
                        pluginId, e.toString(), e);
                cleanUpFailedBringUp(record);
                lifecycleState.set(pluginId, PluginRuntimePhase.STOPPED);
                return;
            }
            record.context = child;
        }
        // c) runtime capability beans from the child context.
        if (record.context != null) {
            try {
                capabilityContributionRegistrar.register(pluginId, record.context);
            } catch (RuntimeException e) {
                log.error("Failed to register runtime capability contributions for plugin '{}': {} - rolling back service footprint.",
                        pluginId, e.toString(), e);
                rollBackBringUp(record, false, false);
                lifecycleState.set(pluginId, PluginRuntimePhase.STOPPED);
                return;
            }
        }
        // d) controller 动态注册进父 context 请求分发表（仅有子 context 的插件包）。
        if (record.context != null) {
            try {
                controllerRegistrar.registerControllers(pluginId, record.context);
            } catch (Exception e) {
                log.error("Failed to register controllers for plugin '{}': {} - rolling back service footprint.",
                        pluginId, e.toString(), e);
                rollBackBringUp(record, true, false);
                lifecycleState.set(pluginId, PluginRuntimePhase.STOPPED);
                return;
            }
        }
        // e) 插件自身 start()：仅运行期重启路径调用（启动期由 PluginRegistry 统一调用、不在此重复）。
        if (invokePluginStart) {
            try {
                record.plugin().ifPresent(PixivFeaturePlugin::start);
            } catch (RuntimeException e) {
                log.error("Plugin '{}' start() failed: {} - rolling back its service footprint.",
                        pluginId, e.toString(), e);
                rollBackBringUp(record, true, false);
                lifecycleState.set(pluginId, PluginRuntimePhase.STOPPED);
                return;
            }
        }
        // f) 最终原子发布 schedule owner bundle。此前失败均不会让调度器看到该 owner。
        if (record.registered != null) {
            try {
                record.schedulePublication = scheduleContributionRegistrar
                        .register(scheduleMutationAuthority, record.registered, record.context).orElse(null);
            } catch (RuntimeException e) {
                log.error("Failed to publish schedule capabilities for plugin '{}': {} - rolling back service footprint.",
                        pluginId, e.toString(), e);
                rollBackBringUp(record, true, invokePluginStart);
                lifecycleState.set(pluginId, PluginRuntimePhase.STOPPED);
                return;
            }
        }
        lifecycleState.transition(pluginId, PluginRuntimePhase.STARTED);
    }

    /**
     * lease 已归零后拆除服务足迹，顺序为 controller → runtime capability → web → 插件 stop → child context。
     * schedule publication 已在 quiesce 起点精确撤回，不在这里按 pluginId 重复注销。
     */
    private void tearDownServing(ManagedPlugin record) {
        String pluginId = record.pluginId;
        try {
            controllerRegistrar.unregisterControllers(pluginId);
        } catch (RuntimeException e) {
            log.warn("Error unregistering controllers for plugin '{}': {}", pluginId, e.toString());
        }
        try {
            capabilityContributionRegistrar.unregister(pluginId);
        } catch (RuntimeException e) {
            log.warn("Error unregistering runtime capability contributions for plugin '{}': {}", pluginId, e.toString());
        }
        try {
            if (record.webRegistered) {
                webContributionRegistrar.unregister(pluginId, classLoaderOf(record));
                record.webRegistered = false;
            }
        } catch (RuntimeException e) {
            log.warn("Error unregistering web contributions for plugin '{}': {}", pluginId, e.toString());
        }
        try {
            record.plugin().ifPresent(PixivFeaturePlugin::stop);
        } catch (RuntimeException e) {
            log.warn("Error invoking stop() on plugin '{}': {}", pluginId, e.toString());
        }
        closeQuietly(record);
    }

    /**
     * 回滚本次 {@link #bringUpServing} 已建立的服务足迹：<b>注销 controller → 按成功标记注销 runtime capability →
     * 关闭 child context → 撤回 web 贡献</b>。能力注册本身失败时由
     * {@link PluginCapabilityContributionRegistrar} 完成内部回滚，此处不再二次注销原子旧快照。用于 schedule 贡献注册失败 /
     * capability 注册失败 / controller 注册失败 / 插件 {@code start()} 失败。各步幂等。仅当运行期插件 {@code start()}
     * 已成功、随后最终 schedule publication 失败时对称调用 {@code stop()}；启动期回调仍归 {@link PluginRegistry} 所有。
     * 每步隔离、异常只记日志。
     */
    private void rollBackBringUp(ManagedPlugin record, boolean capabilitiesRegistered,
                                 boolean pluginStarted) {
        try {
            controllerRegistrar.unregisterControllers(record.pluginId);
        } catch (RuntimeException e) {
            log.warn("Error rolling back controllers for plugin '{}': {}", record.pluginId, e.toString());
        }
        if (capabilitiesRegistered) {
            try {
                capabilityContributionRegistrar.unregister(record.pluginId);
            } catch (RuntimeException e) {
                log.warn("Error rolling back runtime capability contributions for plugin '{}': {}",
                        record.pluginId, e.toString());
            }
        }
        if (pluginStarted) {
            try {
                record.plugin().ifPresent(PixivFeaturePlugin::stop);
            } catch (RuntimeException e) {
                log.warn("Error stopping plugin '{}' after schedule publication failure: {}",
                        record.pluginId, e.toString());
            }
        }
        closeQuietly(record);
        cleanUpFailedBringUp(record);
    }

    /** 失败接入的清理：撤回本次接入的 web 贡献（幂等）。 */
    private void cleanUpFailedBringUp(ManagedPlugin record) {
        try {
            webContributionRegistrar.unregister(record.pluginId, classLoaderOf(record));
        } catch (RuntimeException e) {
            log.warn("Error rolling back web contributions for plugin '{}': {}", record.pluginId, e.toString());
        }
        record.webRegistered = false;
    }

    private void closeQuietly(ManagedPlugin record) {
        ConfigurableApplicationContext child = record.context;
        if (child == null) {
            return;
        }
        if (record.scheduleDrain != null && !record.scheduleDrain.isDrained()) {
            throw new PluginLifecycleException("refusing to close child context with active schedule leases: "
                    + record.pluginId + " (active=" + record.scheduleDrain.activeLeaseCount() + ")");
        }
        try {
            child.close();
        } catch (Exception e) {
            log.warn("Error closing child context for plugin '{}': {}", record.pluginId, e.toString());
        }
        record.context = null;
    }

    private List<PluginRegistry.RegisteredPlugin> externalRegisteredPlugins() {
        return pluginRegistry.registeredPlugins().stream()
                .filter(rp -> rp.source() == PluginSource.EXTERNAL)
                .toList();
    }

    private static PluginRegistry.RegisteredPlugin findRegistered(
            List<PluginRegistry.RegisteredPlugin> external, String pluginId) {
        return external.stream().filter(rp -> rp.id().equals(pluginId)).findFirst().orElse(null);
    }

    private static ClassLoader classLoaderOf(ManagedPlugin record) {
        if (record.module != null) {
            return record.module.classLoader();
        }
        return record.registered != null ? record.registered.classLoader() : null;
    }

    private ManagedPlugin require(String pluginId) {
        ManagedPlugin record = managed.get(pluginId);
        if (record == null) {
            throw new PluginLifecycleException("unknown external plugin: " + pluginId);
        }
        return record;
    }

    /** 一个受管外置插件的可变运行期记录：标识 + 子 context 装配定义（可空）+ 注册条目（可空）+ 当前子 context + web 接入标志。 */
    private static final class ManagedPlugin {
        final String pluginId;
        final long generation;
        final PluginContextModule module;                 // 可空（纯贡献插件无子 context）
        final PluginRegistry.RegisteredPlugin registered; // 可空（无核心注册条目的测试夹具）
        volatile ConfigurableApplicationContext context;  // 不在服务时为空
        boolean webRegistered;
        ScheduleCapabilityPublication schedulePublication;
        ScheduleGenerationDrain scheduleDrain;
        boolean runtimeTasksQuiesced;

        ManagedPlugin(String pluginId, PluginContextModule module, PluginRegistry.RegisteredPlugin registered) {
            this(pluginId, registered != null ? registered.generation() : 0L, module, registered);
        }

        ManagedPlugin(String pluginId, long generation, PluginContextModule module,
                      PluginRegistry.RegisteredPlugin registered) {
            this.pluginId = pluginId;
            this.generation = generation;
            this.module = module;
            this.registered = registered;
        }

        Optional<PixivFeaturePlugin> plugin() {
            return registered == null ? Optional.empty() : Optional.of(registered.plugin());
        }
    }
}
