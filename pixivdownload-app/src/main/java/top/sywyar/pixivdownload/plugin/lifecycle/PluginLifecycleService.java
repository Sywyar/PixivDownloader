package top.sywyar.pixivdownload.plugin.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueDrain;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar.PreparedOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPublication;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestGenerationDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLease;
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
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionHandle;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionRegistrar;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionRegistrar.PreparedWebContribution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

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
 * {@code stop} 依次：<b>① 标记 {@link PluginRuntimePhase#QUIESCED} 并撤回本 serving 的 HTTP 请求准入</b>→
 * <b>② 精确撤回本 generation 的 schedule publication</b>（统一 registry 原子拒绝新 lease，并向旧 lease 发取消）→
 * <b>③ 保存 queue generation drain、关闭 SSE 并发送协作式取消</b> →
 * <b>④ 用同一绝对截止时间等待旧 request / schedule / queue generation 真实归零</b> → <b>⑤ 拆除服务足迹</b>
 * （注销 controller / runtime capability / web 贡献 → 经 {@link PluginRegistry} 调插件 {@code stop()} → 关闭子 context）。
 * schedule 或 queue 准备 / 取消失败不会被吞掉；运行期等待超时返回
 * {@link PluginManagementErrorCode#OPERATION_IN_PROGRESS} 并保持 {@link PluginRuntimePhase#QUIESCED}，绝不关闭仍被
 * schedule lease 或 queue task 引用的 child context。其余足迹拆除继续沿用 best-effort 隔离。
 *
 * <h2>插件自身 {@code start()} / {@code stop()}</h2>
 * 插件自身的生命周期回调与服务足迹（web / controller / 子 context）分属不同时机：<b>启动期</b>全部活动插件的
 * {@code start()} 由 {@link PluginRegistry}（{@code SmartLifecycle}）统一调用一次，故 {@link #startAll()} 只建立服务
 * 足迹、<b>不重复调 {@code start()}</b>（启动期接入路径 {@link #bringUpFromBoot} 传 {@code invokePluginStart=false}）；
 * <b>运行期</b>由本服务编排，但仍统一经 {@link PluginRegistry#startFeature} / {@link PluginRegistry#stopFeature}
 * 调用精确代际回调，使 SmartLifecycle 与热启停共享同一个幂等事实源。应用关闭先由本服务撤回服务足迹，随后
 * {@link PluginRegistry} 只停止仍处于 started 的身份；运行期已经成功停止的身份不会被重复回调。运行期 {@code start()}
 * 若抛异常即回滚本次已建立的足迹（controller / web / 子 context）并落 {@link PluginRuntimePhase#STOPPED}、不进入 STARTED。
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
 *   <li><b>清退在途资源</b>——① 从核心队列宿主注册中心取得 owner 精确操作快照，先保存各操作实例的
 *       {@link QueueDrain}，再发送协作式取消；② 经 {@link PluginStreamRegistry#closeForPlugin} 关闭该插件
 *       全部活动 SSE 推流并向客户端发「插件不可用」事件。生命周期不在 teardown 时重读插件 getter，也不直依赖任一
 *       具体下载实现。</li>
 * </ol>
 * schedule 撤回与 queue drain 保存属于安全前置条件，失败向上抛出；SSE 与队列取消彼此隔离。队列任务的 drain 仅在
 * 核心 wrapper 真正退出后归零，排队任务取消时立即清除插件 delegate。异步任务一律走核心受管执行器，本服务不新建线程池。
 *
 * <p>本服务把外置插件标识为单一 {@code pluginId}：对全部在场外置插件，其功能插件 id 与 PF4J 包 id 重合，故子
 * context / controller / web 贡献 / 路由声明 / 状态机统一以该 id 为键。所有变更在本对象锁内串行；写入各注册中心
 * 复用其内部锁。无外置插件时本服务为空、透明无副作用。本服务不触碰鉴权（{@code AuthFilter} 按
 * {@link RouteAccessRegistry} 独立执行）。
 */
@Slf4j
@Component
public class PluginLifecycleService {

    private static final long RUNTIME_DRAIN_TIMEOUT_NANOS = 30_000_000_000L;

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
    private final Runnable afterCapabilityPublishReturnProbe;
    private final Runnable afterCapabilityWithdrawReturnProbe;
    private final Runnable afterCapabilityRetireReturnProbe;
    private final Runnable afterCapabilityAcknowledgeReturnProbe;
    private final Runnable afterCapabilityAcknowledgeFlagProbe;
    private final ScheduleContributionLifecycleAuthority scheduleMutationAuthority =
            new ScheduleContributionLifecycleAuthority();

    private final Object lock = new Object();
    /** 按接入顺序保存的受管外置插件（键 = pluginId）。仅在 {@link #lock} 内变更，读路径取只读视图。 */
    private final Map<String, ManagedPlugin> managed = new LinkedHashMap<>();
    private volatile boolean started;

    @Autowired
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
        this(parent,
                pluginRuntimeManager,
                contextFactory,
                controllerRegistrar,
                webContributionRegistrar,
                scheduleContributionRegistrar,
                runtimeTaskQuiescer,
                capabilityContributionRegistrar,
                pluginRegistry,
                lifecycleState,
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                });
    }

    PluginLifecycleService(ApplicationContext parent,
                           PluginRuntimeManager pluginRuntimeManager,
                           PluginApplicationContextFactory contextFactory,
                           PluginControllerRegistrar controllerRegistrar,
                           PluginWebContributionRegistrar webContributionRegistrar,
                           PluginScheduleContributionRegistrar scheduleContributionRegistrar,
                           PluginRuntimeTaskQuiescer runtimeTaskQuiescer,
                           PluginCapabilityContributionRegistrar capabilityContributionRegistrar,
                           PluginRegistry pluginRegistry,
                           PluginLifecycleState lifecycleState,
                           Runnable afterCapabilityPublishReturnProbe,
                           Runnable afterCapabilityWithdrawReturnProbe,
                           Runnable afterCapabilityRetireReturnProbe,
                           Runnable afterCapabilityAcknowledgeReturnProbe,
                           Runnable afterCapabilityAcknowledgeFlagProbe) {
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
        this.afterCapabilityPublishReturnProbe = Objects.requireNonNull(
                afterCapabilityPublishReturnProbe, "capability publish return probe");
        this.afterCapabilityWithdrawReturnProbe = Objects.requireNonNull(
                afterCapabilityWithdrawReturnProbe, "capability withdraw return probe");
        this.afterCapabilityRetireReturnProbe = Objects.requireNonNull(
                afterCapabilityRetireReturnProbe, "capability retire return probe");
        this.afterCapabilityAcknowledgeReturnProbe = Objects.requireNonNull(
                afterCapabilityAcknowledgeReturnProbe, "capability acknowledge return probe");
        this.afterCapabilityAcknowledgeFlagProbe = Objects.requireNonNull(
                afterCapabilityAcknowledgeFlagProbe, "capability acknowledge flag probe");
    }

    // ---- 启动期接入 / 关闭期收尾（由 ExternalPluginContextManager 驱动）----

    /**
     * 启动期只为核心注册中心活动快照中的外置插件建立服务足迹。声明了配置类的活动插件包建立子 context，
     * 再由同一 owner/generation 动态发布 controller、Web、计划及 child-context 能力；无配置类插件仍发布其
     * feature 元数据 contribution。单个插件接入失败被隔离，不致核心壳启动失败。
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
                PluginRegistry.RegisteredPlugin registered = findRegistered(external, pluginId);
                if (registered == null) {
                    log.debug("Skipping inactive external plugin context module '{}'.", pluginId);
                    continue;
                }
                if (managed.containsKey(pluginId)) {
                    log.warn("Duplicate plugin context module for '{}' - keeping the first, skipping.", pluginId);
                    continue;
                }
                ManagedPlugin record = new ManagedPlugin(pluginId, module, registered);
                managed.put(pluginId, record);
                bringUpFromBoot(record);
            }
            // 2) 没有子 context 的纯贡献外置插件：仍由最终 bring-up 点动态发布 feature 元数据 contribution。
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
            Throwable fatal = null;
            boolean interrupted = false;
            // 先对全部插件撤回 publication 并发出取消，避免逐插件等待时其它 owner 仍继续接收新执行。
            List<String> pendingQuiesce = new ArrayList<>();
            for (String id : ids) {
                PluginRuntimePhase phase = lifecycleState.phase(id).orElse(null);
                if (phase == PluginRuntimePhase.STARTED || phase == PluginRuntimePhase.QUIESCED) {
                    pendingQuiesce.add(id);
                }
            }
            while (!pendingQuiesce.isEmpty()) {
                for (int index = 0; index < pendingQuiesce.size();) {
                    String id = pendingQuiesce.get(index);
                    ManagedPlugin record = managed.get(id);
                    try {
                        ensureQuiesced(record);
                        if (record.requestWithdrawalComplete && record.capabilityWithdrawalComplete
                                && record.scheduleWithdrawalComplete
                                && record.queuePreparationComplete
                                && record.runtimeTasksQuiesced) {
                            pendingQuiesce.remove(index);
                        } else {
                            index++;
                        }
                    } catch (Throwable failure) {
                        fatal = mergeFatal(fatal, failure);
                        log.warn("Core shutdown quiesce remains incomplete for plugin '{}' (failureType={}).",
                                id, failure.getClass().getName());
                        index++;
                    }
                }
                if (!pendingQuiesce.isEmpty()) {
                    interrupted |= parkShutdownRetry();
                }
            }

            // 核心 context 关闭不能在 lease 仍活动时继续销毁父服务；忽略中断直到所有旧代执行真实归零。
            for (String id : ids) {
                ManagedPlugin record = managed.get(id);
                PluginRuntimePhase phase = lifecycleState.phase(id).orElse(null);
                if (phase == PluginRuntimePhase.QUIESCED
                        && record.requestWithdrawalComplete && record.capabilityWithdrawalComplete
                        && record.scheduleWithdrawalComplete
                        && record.queuePreparationComplete
                        && record.runtimeTasksQuiesced) {
                    interrupted |= awaitRuntimeDrainsUninterruptibly(record);
                    while (lifecycleState.phase(id).orElse(null) == PluginRuntimePhase.QUIESCED) {
                        try {
                            finishStop(record, false);
                        } catch (Throwable failure) {
                            fatal = mergeFatal(fatal, failure);
                            log.warn("Core shutdown service cleanup remains incomplete for plugin '{}' (failureType={}).",
                                    id, failure.getClass().getName());
                            interrupted |= parkShutdownRetry();
                        }
                    }
                }
            }
            started = false;
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (fatal != null) {
                rethrowFatal(fatal);
            }
        }
    }

    private static boolean parkShutdownRetry() {
        boolean interrupted = Thread.interrupted();
        LockSupport.parkNanos(1_000_000L);
        return interrupted | Thread.interrupted();
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
            PluginContextModule module = loaded.contextModules().isEmpty() ? null : loaded.contextModules().get(0);
            ManagedPlugin record = new ManagedPlugin(packageId, loaded.generation(), module, registered);
            Optional<PluginRuntimePhase> previousPhase = lifecycleState.phase(packageId);
            try {
                pluginRegistry.register(registered);
                managed.put(packageId, record);
                lifecycleState.initialize(packageId, PluginRuntimePhase.LOADED);
            } catch (Throwable failure) {
                Throwable cleanupFailure = null;
                try {
                    if (previousPhase.isPresent()) {
                        lifecycleState.set(packageId, previousPhase.get());
                    } else {
                        lifecycleState.remove(packageId);
                    }
                } catch (Throwable cleanup) {
                    cleanupFailure = mergeFailure(cleanupFailure, cleanup);
                }
                if (managed.get(packageId) == record) {
                    managed.remove(packageId);
                }
                cleanupFailure = unregisterAfterFailedRegistration(registered, cleanupFailure);
                throwCompensatedLifecycleFailure(
                        "failed to adopt new generation for '" + packageId + "'", failure, cleanupFailure);
            }
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

    /** 供物理卸载编排在跨越边界前复核本代精确核心身份已经删除。 */
    public boolean coreRegistrationPresent(String packageId, long generation) {
        synchronized (lock) {
            ManagedPlugin record = managed.get(packageId);
            return record != null && record.generation == generation && record.registered != null
                    && pluginRegistry.containsIdentity(record.registered);
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

    /**
     * Runs host-owned synchronous work against one active child context under that exact serving's request lease.
     * Returning {@code false} means the plugin is absent, quiesced, context-free, or lost admission before the
     * operation started; in every case the callback is left untouched.
     */
    public boolean withServingContext(
            String pluginId,
            Consumer<ConfigurableApplicationContext> operation) {
        Objects.requireNonNull(operation, "serving context operation");
        ConfigurableApplicationContext context = null;
        PluginRequestLease lease = null;
        try {
            synchronized (lock) {
                ManagedPlugin record = managed.get(pluginId);
                if (record == null
                        || lifecycleState.phase(pluginId).orElse(null) != PluginRuntimePhase.STARTED
                        || record.context == null
                        || record.webHandle == null) {
                    return false;
                }
                Optional<PluginRequestLease> prepared =
                        webContributionRegistrar.prepareRequestLease(record.webHandle);
                if (prepared.isEmpty()) {
                    return false;
                }
                lease = prepared.orElseThrow();
                if (!webContributionRegistrar.activateRequestLease(record.webHandle, lease)) {
                    return false;
                }
                context = record.context;
            }
            operation.accept(context);
            return true;
        } finally {
            if (lease != null) {
                lease.close();
            }
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
        long deadline = System.nanoTime() + RUNTIME_DRAIN_TIMEOUT_NANOS;
        String activeDrain = awaitRuntimeDrains(record, deadline);
        if (activeDrain != null) {
            throw new ClassifiedPluginLifecycleException(
                    PluginManagementErrorCode.OPERATION_IN_PROGRESS,
                    "plugin '" + pluginId + "' remains quiesced with active " + activeDrain);
        }
        finishStop(record, true);
    }

    /** 标记 QUIESCED，并且恰好一次按 request → capability → schedule → queue 的顺序发起清退。 */
    private void ensureQuiesced(ManagedPlugin record) {
        PluginRuntimePhase phase = lifecycleState.phase(record.pluginId).orElse(null);
        if (phase == PluginRuntimePhase.STARTED) {
            lifecycleState.transition(record.pluginId, PluginRuntimePhase.QUIESCED);
        } else if (phase != PluginRuntimePhase.QUIESCED) {
            throw new PluginLifecycleException("cannot quiesce plugin '" + record.pluginId
                    + "' from phase " + phase);
        }
        if (!record.requestWithdrawalComplete) {
            PluginWebContributionHandle handle = record.webHandle;
            record.requestDrain = handle == null
                    ? null
                    : webContributionRegistrar.withdrawRequests(handle).orElse(null);
            record.requestWithdrawalComplete = true;
        }
        if (!record.capabilityWithdrawalComplete) {
            record.capabilityDrain = withdrawCapabilityPublication(record, "withdrawal");
            record.capabilityWithdrawalComplete = true;
        }
        if (!record.scheduleWithdrawalComplete) {
            record.scheduleDrain = withdrawScheduleOrRetryFailedRegistration(record);
            record.scheduleWithdrawalComplete = true;
        }
        if (!record.queuePreparationComplete) {
            runtimeTaskQuiescer.prepareQueueDrains(
                    record.pluginId, List.copyOf(record.queueDrains), record.queueDrains::add);
            record.queuePreparationComplete = true;
        }
        if (record.runtimeTasksQuiesced) {
            return;
        }
        runtimeTaskQuiescer.quiesceAfterScheduleWithdrawal(
                record.pluginId, List.copyOf(record.queueDrains));
        record.runtimeTasksQuiesced = true;
    }

    /** Recover a publication returned before lifecycle assignment, then withdraw the same exact central drain. */
    private ExternalCapabilityDrain withdrawCapabilityPublication(ManagedPlugin record, String action) {
        ExternalCapabilityPublication publication = recoverCapabilityPublication(record);
        if (publication == null) {
            return null;
        }
        record.capabilityRetirementComplete = false;
        record.capabilityRetirementAcknowledged = false;
        ExternalCapabilityDrain drain = capabilityContributionRegistrar.withdraw(publication)
                .orElseThrow(() -> new IllegalStateException(
                        "missing external capability publication during " + action + ": "
                                + publication.owner()));
        afterCapabilityWithdrawReturnProbe.run();
        record.capabilityDrain = drain;
        return drain;
    }

    /** Publication recovery always normalizes the retirement flags before exposing the recovered token. */
    private ExternalCapabilityPublication recoverCapabilityPublication(ManagedPlugin record) {
        ExternalCapabilityPublication publication = record.capabilityPublication;
        PreparedOwner preparation = record.capabilityPreparation;
        if (publication == null && preparation != null) {
            publication = capabilityContributionRegistrar.recoverPublication(preparation).orElse(null);
            if (publication != null) {
                record.capabilityRetirementComplete = false;
                record.capabilityRetirementAcknowledged = false;
                record.capabilityPublication = publication;
            }
        }
        if (publication != null && preparation != null) {
            record.capabilityPreparation = null;
        }
        return publication;
    }

    /** Discard only a confirmed unpublished preparation; a successful publication is recovered instead. */
    private void discardCapabilityPreparation(ManagedPlugin record) {
        PreparedOwner preparation = record.capabilityPreparation;
        if (preparation == null) {
            return;
        }
        if (recoverCapabilityPublication(record) != null) {
            return;
        }
        if (!capabilityContributionRegistrar.discardUnpublished(preparation)) {
            if (recoverCapabilityPublication(record) != null) {
                return;
            }
            throw new IllegalStateException("external capability preparation cleanup remains pending: "
                    + preparation.owner());
        }
        record.capabilityPreparation = null;
        record.capabilityRetirementComplete = true;
        record.capabilityRetirementAcknowledged = true;
    }

    /** 全部 runtime drain 已归零后的唯一服务足迹关闭入口；核心关闭把 feature stop 留给后续 Registry phase。 */
    private void finishStop(ManagedPlugin record, boolean invokeFeatureStop) {
        assertRuntimeDrained(record);
        // Request drain 归零后再封一次 stream：覆盖首次 closeForPlugin 返回后由旧请求迟到登记的失败回调。
        runtimeTaskQuiescer.quiesceStreams(record.pluginId);
        tearDownServing(record, invokeFeatureStop);
        resetRuntimeDrainState(record);
        lifecycleState.set(record.pluginId, PluginRuntimePhase.STOPPED);
        log.info("Stopped plugin '{}'.", record.pluginId);
    }

    private static void resetRuntimeDrainState(ManagedPlugin record) {
        record.requestDrain = null;
        record.requestWithdrawalComplete = false;
        record.capabilityPreparation = null;
        record.capabilityPublication = null;
        record.capabilityDrain = null;
        record.capabilityWithdrawalComplete = false;
        record.capabilityRetirementComplete = true;
        record.capabilityRetirementAcknowledged = true;
        record.schedulePublication = null;
        record.scheduleDrain = null;
        record.scheduleWithdrawalComplete = false;
        record.scheduleRetirementAcknowledged = true;
        record.queueDrains.clear();
        record.queuePreparationComplete = false;
        record.runtimeTasksQuiesced = false;
    }

    /** 对 request、schedule 与全部 queue drain 使用同一个绝对截止时间；返回首个仍活动的纯值诊断。 */
    private static String awaitRuntimeDrains(ManagedPlugin record, long deadlineNanos) {
        PluginRequestGenerationDrain requestDrain = record.requestDrain;
        if (requestDrain != null && !requestDrain.awaitDrained(deadlineNanos)) {
            return "request lease(s)=" + requestDrain.activeLeaseCount();
        }
        ExternalCapabilityDrain capabilityDrain = record.capabilityDrain;
        if (capabilityDrain != null && !capabilityDrain.awaitDrained(deadlineNanos)) {
            return "capability invocation(s)=" + capabilityDrain.activeLeaseCount();
        }
        ScheduleGenerationDrain drain = record.scheduleDrain;
        if (drain != null && !drain.awaitDrained(deadlineNanos)) {
            return "schedule lease(s)=" + drain.activeLeaseCount();
        }
        for (QueueDrain queueDrain : record.queueDrains) {
            if (!queueDrain.awaitDrained(deadlineNanos)) {
                return "queue task(s) " + queueDrain.queueType() + "=" + queueDrain.activeCount();
            }
        }
        return null;
    }

    /** clean context shutdown 使用：清除中断并持续等待全部 runtime drain，返回是否曾观察到中断。 */
    private static boolean awaitRuntimeDrainsUninterruptibly(ManagedPlugin record) {
        boolean interrupted = false;
        PluginRequestGenerationDrain requestDrain = record.requestDrain;
        while (requestDrain != null && !requestDrain.isDrained()) {
            if (!requestDrain.awaitDrained()) {
                interrupted |= Thread.interrupted();
            }
        }
        ExternalCapabilityDrain capabilityDrain = record.capabilityDrain;
        while (capabilityDrain != null && !capabilityDrain.isDrained()) {
            if (!capabilityDrain.awaitDrained()) {
                interrupted |= Thread.interrupted();
            }
        }
        ScheduleGenerationDrain drain = record.scheduleDrain;
        while (drain != null && !drain.isDrained()) {
            if (!drain.awaitDrained()) {
                interrupted |= Thread.interrupted();
            }
        }
        for (QueueDrain queueDrain : record.queueDrains) {
            while (!queueDrain.isDrained()) {
                if (!queueDrain.awaitDrained()) {
                    interrupted |= Thread.interrupted();
                }
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
        if (record.registered != null && pluginRegistry.containsIdentity(record.registered)) {
            try {
                pluginRegistry.unregister(record.registered);
            } catch (Throwable failure) {
                rethrowFatal(failure);
                throw new PluginLifecycleException(
                        "failed to unregister exact core identity for plugin '" + pluginId
                                + "' (failureType=" + failure.getClass().getName() + ")");
            }
        }
        if (record.registered != null && pluginRegistry.containsIdentity(record.registered)) {
            throw new PluginLifecycleException(
                    "exact core registration remains present for plugin '" + pluginId + "'");
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
                lifecycleState.transition(pluginId, PluginRuntimePhase.LOADED);
            } catch (Throwable failure) {
                Throwable cleanupFailure = unregisterAfterFailedRegistration(record.registered, null);
                throwCompensatedLifecycleFailure(
                        "failed to re-register plugin '" + pluginId + "' into core registry",
                        failure, cleanupFailure);
            }
        } else {
            lifecycleState.transition(pluginId, PluginRuntimePhase.LOADED);
        }
        log.info("Loaded plugin '{}' into core registry.", pluginId);
    }

    // ---- 内部：服务足迹建立 / 拆除 ----

    /**
     * 启动期接入：阶段从 LOADED 起步，web 贡献已在各注册中心构造期接入故不重复接入；插件自身 {@code start()} 已由
     * {@link PluginRegistry}（{@code SmartLifecycle}）在启动期统一调用，故本路径<b>不重复调 {@code start()}</b>
     * （区别于运行期 {@link #doStart}）。
     */
    private void bringUpFromBoot(ManagedPlugin record) {
        lifecycleState.initialize(record.pluginId, PluginRuntimePhase.LOADED);
        if (record.registered != null) {
            try {
                record.webHandle = webContributionRegistrar.currentHandle(record.registered)
                        .orElseThrow(() -> new IllegalStateException(
                                "missing boot web contribution handle for plugin: " + record.pluginId));
            } catch (Throwable failure) {
                log.error("Failed to capture boot web contribution handle for plugin '{}' (failureType={}) - isolating.",
                        record.pluginId, failure.getClass().getName());
                handleBringUpFailure(
                        record, failure, pluginRegistry.featureStarted(record.registered));
                return;
            }
        }
        bringUpServing(record, /* invokePluginStart */ false); // 启动期 start() 归 PluginRegistry，本服务不重复
    }

    /**
     * 建立服务足迹：建子 context → 接入 runtime capability → 动态注册 controller →
     *（仅运行期重启）调插件 {@code start()} → 接入 web/download 贡献 → 最终原子发布 schedule owner bundle，成功后阶段落到
     * {@link PluginRuntimePhase#STARTED}。任一步失败都尝试隔离：清理完整时落 STOPPED；若某个有状态 registry
     * 尚未达成删除后置条件，则保留句柄、context 与插件并落 QUIESCED，向上抛出以阻断 unload，供后续 stop 重试。
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
        PreparedWebContribution preparedWeb = null;
        boolean featureStartedForRollback = !invokePluginStart && record.registered != null
                && pluginRegistry.featureStarted(record.registered);
        // a) 子 context（仅声明了配置类的插件包）。
        if (record.module != null) {
            ConfigurableApplicationContext child;
            try {
                child = contextFactory.create(parent, record.module);
            } catch (Throwable failure) {
                log.error("Failed to start child context for plugin '{}' (failureType={}) - isolating.",
                        pluginId, failure.getClass().getName());
                handleBringUpFailure(record, failure, featureStartedForRollback);
                return;
            }
            record.context = child;
        }
        // b) Prepare migrated runtime capability proxies without admission, then retain the legacy queue bridge.
        if (record.context != null) {
            try {
                String packageId = record.registered == null
                        ? pluginId : record.registered.packageId();
                record.capabilityPreparation = capabilityContributionRegistrar.allocateOwner(
                        pluginId, packageId, record.generation);
                if (record.capabilityPreparation != null) {
                    record.capabilityRetirementComplete = false;
                    record.capabilityRetirementAcknowledged = false;
                    capabilityContributionRegistrar.prepareInto(
                            record.capabilityPreparation, record.context);
                }
                record.capabilityCleanupComplete = false;
                capabilityContributionRegistrar.registerLegacy(pluginId, record.context);
            } catch (Throwable failure) {
                log.error("Failed to prepare runtime capability contributions for plugin '{}' (failureType={}) - rolling back service footprint.",
                        pluginId, failure.getClass().getName());
                handleBringUpFailure(record, failure, featureStartedForRollback);
                return;
            }
        }
        // c) 运行期先一次性读取 getter / 解析资源 / 校验下载扩展；此时任何 web/download 快照都尚未公开。
        if (record.webHandle == null && record.registered != null) {
            try {
                preparedWeb = webContributionRegistrar.prepare(record.registered);
            } catch (Throwable failure) {
                log.error("Failed to prepare web contributions for plugin '{}' (failureType={}) - rolling back service footprint.",
                        pluginId, failure.getClass().getName());
                handleBringUpFailure(record, failure, featureStartedForRollback);
                return;
            }
        }
        // d) controller 动态注册；运行期 route 尚未发布，校验使用同一 prepared token 的路由快照。
        if (record.context != null) {
            record.controllerCleanupComplete = false;
            try {
                if (preparedWeb == null) {
                    controllerRegistrar.registerControllers(pluginId, record.context);
                } else {
                    controllerRegistrar.registerControllers(pluginId, record.context, preparedWeb);
                }
            } catch (Throwable failure) {
                log.error("Failed to register controllers for plugin '{}' (failureType={}) - rolling back service footprint.",
                        pluginId, failure.getClass().getName());
                handleBringUpFailure(record, failure, featureStartedForRollback);
                return;
            }
        }
        // e) 插件自身 start()：仅运行期重启路径经 PluginRegistry 的精确身份入口调用。
        if (invokePluginStart) {
            try {
                if (record.registered != null) {
                    featureStartedForRollback = pluginRegistry.startFeature(record.registered);
                }
            } catch (Throwable failure) {
                log.error("Plugin '{}' start() failed (failureType={}) - rolling back its service footprint.",
                        pluginId, failure.getClass().getName());
                handleBringUpFailure(record, failure, featureStartedForRollback);
                return;
            }
        }
        // f) 发布 serving 前重新开放推流 admission；失败残留未清零时拒绝混入新 serving。
        try {
            runtimeTaskQuiescer.resumeStreams(pluginId);
        } catch (Throwable failure) {
            log.error("Failed to resume server-push streams for plugin '{}' (failureType={}) - rolling back service footprint.",
                    pluginId, failure.getClass().getName());
            handleBringUpFailure(record, failure, featureStartedForRollback);
            return;
        }
        // g) 运行期在 context/capability/controller/plugin 全部就绪后才提交同一份 prepared web/download。
        // 启动期构造期已接入并保留精确句柄，故跳过。
        if (record.webHandle == null && record.registered != null) {
            try {
                record.webHandle = Objects.requireNonNull(
                        webContributionRegistrar.commit(Objects.requireNonNull(preparedWeb, "prepared web contribution")),
                        "web contribution registrar returned null handle");
            } catch (Throwable failure) {
                record.webHandle = webContributionRegistrar.currentHandle(record.registered).orElse(null);
                log.error("Failed to register web contributions for plugin '{}' (failureType={}) - isolating.",
                        pluginId, failure.getClass().getName());
                if (record.webHandle != null) {
                    lifecycleState.set(pluginId, PluginRuntimePhase.QUIESCED);
                    if (isFatal(failure)) {
                        rethrowFatal(failure);
                    }
                    throw new PluginLifecycleException(
                            "web contribution registration cleanup remains pending for plugin '"
                                    + pluginId + "' (failureType=" + failure.getClass().getName() + ")");
                }
                handleBringUpFailure(record, failure, featureStartedForRollback);
                return;
            }
        }
        // h) Publish schedule only after the complete web/controller serving is ready.
        if (record.registered != null) {
            try {
                record.schedulePublication = scheduleContributionRegistrar
                        .register(scheduleMutationAuthority, record.registered, record.context).orElse(null);
                if (record.schedulePublication != null) {
                    record.scheduleRetirementAcknowledged = false;
                }
            } catch (Throwable failure) {
                log.error("Failed to publish schedule capabilities for plugin '{}' (failureType={}) - rolling back service footprint.",
                        pluginId, failure.getClass().getName());
                handleBringUpFailure(record, failure, featureStartedForRollback);
                return;
            }
        }
        // i) Open migrated capability admission last; every downstream proxy snapshot was prepared but invisible.
        if (record.capabilityPreparation != null) {
            try {
                ExternalCapabilityPublication publication = Objects.requireNonNull(
                        capabilityContributionRegistrar.publish(record.capabilityPreparation),
                        "capability registrar returned null publication");
                afterCapabilityPublishReturnProbe.run();
                record.capabilityPublication = publication;
                record.capabilityPreparation = null;
            } catch (Throwable failure) {
                log.error("Failed to publish runtime capability contributions for plugin '{}' (failureType={}) - rolling back service footprint.",
                        pluginId, failure.getClass().getName());
                handleBringUpFailure(record, failure, featureStartedForRollback);
                return;
            }
        }
        // 统一 stop 对所有插件执行幂等注销；纯贡献插件虽无 child context，adapter / controller registrar 仍按 id 清理。
        record.controllerCleanupComplete = false;
        record.capabilityCleanupComplete = record.context == null;
        try {
            lifecycleState.transition(pluginId, PluginRuntimePhase.STARTED);
        } catch (Throwable failure) {
            handleBringUpFailure(record, failure, featureStartedForRollback);
        }
    }

    /**
     * lease 已归零后拆除服务足迹，顺序为 web/download → controller → runtime capability → 可选 feature stop → child context。
     * schedule publication 已在 quiesce 起点精确撤回，不在这里按 pluginId 重复注销。运行期 stop 请求 feature stop；
     * 核心关闭则先撤回服务足迹，由随后执行的 PluginRegistry phase 停止仍 started 的精确身份。
     */
    private void tearDownServing(ManagedPlugin record, boolean invokeFeatureStop) {
        String pluginId = record.pluginId;
        // serving 句柄仍 current 时不破坏 controller/capability/plugin/context，保留完整 QUIESCED 足迹供重试。
        withdrawWebContribution(record, "unregistering");
        Throwable fatal = null;
        List<String> pending = new ArrayList<>();
        if (record.capabilityPreparation != null) {
            try {
                discardCapabilityPreparation(record);
            } catch (Throwable failure) {
                fatal = mergeFatal(fatal, failure);
                pending.add("capability-preparation=" + failure.getClass().getName());
            }
        }
        if (!record.controllerCleanupComplete) {
            try {
                controllerRegistrar.unregisterControllers(pluginId);
                record.controllerCleanupComplete = true;
            } catch (Throwable failure) {
                fatal = mergeFatal(fatal, failure);
                pending.add("controller=" + failure.getClass().getName());
            }
        }
        if (!record.capabilityCleanupComplete) {
            try {
                capabilityContributionRegistrar.unregisterLegacy(pluginId);
                record.capabilityCleanupComplete = true;
            } catch (Throwable failure) {
                fatal = mergeFatal(fatal, failure);
                pending.add("capability=" + failure.getClass().getName());
            }
        }
        if (!record.capabilityRetirementComplete && record.capabilityDrain != null) {
            try {
                ExternalCapabilityDrain drain = Objects.requireNonNull(
                        record.capabilityDrain, "missing external capability drain");
                capabilityContributionRegistrar.retireDrained(drain);
                afterCapabilityRetireReturnProbe.run();
                record.capabilityRetirementComplete = true;
            } catch (Throwable failure) {
                fatal = mergeFatal(fatal, failure);
                pending.add("capability-retirement=" + failure.getClass().getName());
            }
        }
        if (!record.capabilityRetirementAcknowledged && record.capabilityDrain != null) {
            try {
                ExternalCapabilityDrain drain = Objects.requireNonNull(
                        record.capabilityDrain, "missing acknowledged external capability drain");
                capabilityContributionRegistrar.acknowledgeRetired(drain);
                afterCapabilityAcknowledgeReturnProbe.run();
                record.capabilityRetirementAcknowledged = true;
                afterCapabilityAcknowledgeFlagProbe.run();
            } catch (Throwable failure) {
                fatal = mergeFatal(fatal, failure);
                pending.add("capability-acknowledgement=" + failure.getClass().getName());
            }
        }
        if (!record.scheduleRetirementAcknowledged && record.scheduleDrain != null) {
            try {
                scheduleContributionRegistrar.acknowledgeRetired(
                        scheduleMutationAuthority, record.scheduleDrain);
                record.scheduleRetirementAcknowledged = true;
            } catch (Throwable failure) {
                fatal = mergeFatal(fatal, failure);
                pending.add("schedule-acknowledgement=" + failure.getClass().getName());
            }
        }
        if (fatal != null) {
            rethrowFatal(fatal);
        }
        if (!pending.isEmpty()) {
            throw new PluginLifecycleException("service footprint cleanup remains pending for plugin '"
                    + pluginId + "': " + pending);
        }
        if (invokeFeatureStop && record.registered != null) {
            pluginRegistry.stopFeature(record.registered);
        }
        closeQuietly(record);
    }

    /**
     * 回滚本次 {@link #bringUpServing} 已建立的服务足迹：<b>先精确撤回 web/download serving，再注销
     * controller → 按成功标记注销 runtime capability → 停止插件 → 关闭 child context</b>。能力注册本身失败时由
     * {@link PluginCapabilityContributionRegistrar} 完成内部回滚，此处不再二次注销原子旧快照。用于 schedule 贡献注册失败 /
     * capability 注册失败 / controller 注册失败 / 插件 {@code start()} 失败。各步幂等。仅当运行期插件 {@code start()}
     * 已成功、随后最终 schedule publication 失败时对称调用 {@code stop()}；启动期回调仍归 {@link PluginRegistry} 所有。
     * 若 serving 撤回失败且原句柄仍是 current，先保留已启动的插件与 context 并落 {@link PluginRuntimePhase#QUIESCED}，
     * 再向上抛出以阻断 unload；故障解除后可通过 stop 精确重试。其余各步隔离、异常只记日志。
     */
    private void rollBackBringUp(ManagedPlugin record, boolean capabilitiesRegistered,
                                 boolean pluginStarted) {
        try {
            cleanUpFailedBringUp(record);
        } catch (PluginLifecycleException failure) {
            lifecycleState.set(record.pluginId, PluginRuntimePhase.QUIESCED);
            throw failure;
        }
        Throwable fatal = null;
        List<String> pending = new ArrayList<>();
        if (record.capabilityPreparation != null) {
            try {
                discardCapabilityPreparation(record);
            } catch (Throwable failure) {
                fatal = mergeFatal(fatal, failure);
                pending.add("capability-preparation=" + failure.getClass().getName());
            }
        }
        if (!record.controllerCleanupComplete) {
            try {
                controllerRegistrar.unregisterControllers(record.pluginId);
                record.controllerCleanupComplete = true;
            } catch (Throwable failure) {
                fatal = mergeFatal(fatal, failure);
                pending.add("controller=" + failure.getClass().getName());
            }
        }
        if (!record.capabilityCleanupComplete) {
            try {
                capabilityContributionRegistrar.unregisterLegacy(record.pluginId);
                record.capabilityCleanupComplete = true;
            } catch (Throwable failure) {
                fatal = mergeFatal(fatal, failure);
                pending.add("capability=" + failure.getClass().getName());
            }
        }
        if (!record.capabilityRetirementComplete && record.capabilityDrain != null) {
            try {
                ExternalCapabilityDrain drain = Objects.requireNonNull(
                        record.capabilityDrain, "missing external capability drain");
                capabilityContributionRegistrar.retireDrained(drain);
                afterCapabilityRetireReturnProbe.run();
                record.capabilityRetirementComplete = true;
            } catch (Throwable failure) {
                fatal = mergeFatal(fatal, failure);
                pending.add("capability-retirement=" + failure.getClass().getName());
            }
        }
        if (!record.capabilityRetirementAcknowledged && record.capabilityDrain != null) {
            try {
                ExternalCapabilityDrain drain = Objects.requireNonNull(
                        record.capabilityDrain, "missing acknowledged external capability drain");
                capabilityContributionRegistrar.acknowledgeRetired(drain);
                afterCapabilityAcknowledgeReturnProbe.run();
                record.capabilityRetirementAcknowledged = true;
                afterCapabilityAcknowledgeFlagProbe.run();
            } catch (Throwable failure) {
                fatal = mergeFatal(fatal, failure);
                pending.add("capability-acknowledgement=" + failure.getClass().getName());
            }
        }
        if (!record.scheduleRetirementAcknowledged && record.scheduleDrain != null) {
            try {
                scheduleContributionRegistrar.acknowledgeRetired(
                        scheduleMutationAuthority, record.scheduleDrain);
                record.scheduleRetirementAcknowledged = true;
            } catch (Throwable failure) {
                fatal = mergeFatal(fatal, failure);
                pending.add("schedule-acknowledgement=" + failure.getClass().getName());
            }
        }
        if (fatal != null) {
            lifecycleState.set(record.pluginId, PluginRuntimePhase.QUIESCED);
            rethrowFatal(fatal);
        }
        if (!pending.isEmpty()) {
            lifecycleState.set(record.pluginId, PluginRuntimePhase.QUIESCED);
            throw new PluginLifecycleException("bring-up cleanup remains pending for plugin '"
                    + record.pluginId + "': " + pending);
        }
        if (pluginStarted && record.registered != null) {
            pluginRegistry.stopFeature(record.registered);
        }
        try {
            closeQuietly(record);
            resetRuntimeDrainState(record);
        } catch (Throwable failure) {
            lifecycleState.set(record.pluginId, PluginRuntimePhase.QUIESCED);
            throw failure;
        }
    }

    /**
     * Bring-up may fail after web, schedule, or capability publication. Withdraw every successful admission in
     * the normal stop order and wait on one deadline before rollback is allowed to close child-owned objects.
     */
    private Throwable quiesceFailedBringUpRuntime(ManagedPlugin record) {
        Throwable failure = null;
        if (!record.requestWithdrawalComplete) {
            try {
                PluginWebContributionHandle handle = record.webHandle;
                record.requestDrain = handle == null
                        ? null
                        : webContributionRegistrar.withdrawRequests(handle).orElse(null);
                record.requestWithdrawalComplete = true;
            } catch (Throwable withdrawalFailure) {
                failure = mergeFailure(failure, withdrawalFailure);
            }
        }
        if (!record.capabilityWithdrawalComplete) {
            try {
                record.capabilityDrain = withdrawCapabilityPublication(record, "rollback");
                record.capabilityWithdrawalComplete = true;
            } catch (Throwable withdrawalFailure) {
                failure = mergeFailure(failure, withdrawalFailure);
            }
        }
        if (!record.scheduleWithdrawalComplete) {
            try {
                record.scheduleDrain = withdrawScheduleOrRetryFailedRegistration(record);
                record.scheduleWithdrawalComplete = true;
            } catch (Throwable withdrawalFailure) {
                failure = mergeFailure(failure, withdrawalFailure);
            }
        }
        if (!record.queuePreparationComplete) {
            try {
                runtimeTaskQuiescer.prepareQueueDrains(
                        record.pluginId, List.copyOf(record.queueDrains), record.queueDrains::add);
                record.queuePreparationComplete = true;
            } catch (Throwable preparationFailure) {
                failure = mergeFailure(failure, preparationFailure);
            }
        }
        if (record.requestWithdrawalComplete
                && record.capabilityWithdrawalComplete
                && record.scheduleWithdrawalComplete
                && record.queuePreparationComplete
                && !record.runtimeTasksQuiesced) {
            try {
                runtimeTaskQuiescer.quiesceAfterScheduleWithdrawal(
                        record.pluginId, List.copyOf(record.queueDrains));
                record.runtimeTasksQuiesced = true;
            } catch (Throwable quiesceFailure) {
                failure = mergeFailure(failure, quiesceFailure);
            }
        }
        if (failure == null && record.runtimeTasksQuiesced) {
            long deadline = System.nanoTime() + RUNTIME_DRAIN_TIMEOUT_NANOS;
            String activeDrain = awaitRuntimeDrains(record, deadline);
            if (activeDrain != null) {
                failure = new ClassifiedPluginLifecycleException(
                        PluginManagementErrorCode.OPERATION_IN_PROGRESS,
                        "bring-up rollback for plugin '" + record.pluginId
                                + "' remains quiesced with active " + activeDrain);
            }
        }
        return failure;
    }

    private ScheduleGenerationDrain withdrawScheduleOrRetryFailedRegistration(ManagedPlugin record) {
        ScheduleCapabilityPublication publication = record.schedulePublication;
        PluginRegistry.RegisteredPlugin registered = record.registered;
        ScheduleCapabilityOwner owner = registered == null ? null : new ScheduleCapabilityOwner(
                registered.id(), registered.packageId(), registered.generation());
        if (publication == null && owner != null) {
            publication = scheduleContributionRegistrar.recoverPublication(
                    scheduleMutationAuthority, owner).orElse(null);
            if (publication != null) {
                record.schedulePublication = publication;
            }
        }
        if (publication != null) {
            record.scheduleRetirementAcknowledged = false;
            PluginRuntimeTaskQuiescer.QuiesceResult result = runtimeTaskQuiescer.withdrawSchedule(
                    scheduleMutationAuthority, publication);
            // 必须先保存唯一 drain，再进入可能抛 fatal 的 SSE / queue 清退；重试绝不二次撤回 publication。
            return result.scheduleDrain().orElse(null);
        }
        if (owner != null) {
            scheduleContributionRegistrar.retryFailedRegistrationCleanup(
                    scheduleMutationAuthority, owner);
        }
        record.scheduleRetirementAcknowledged = true;
        return null;
    }

    /** 先完成安全回滚并保存 pending 状态；首个 fatal 最后按原对象身份重抛。 */
    private void handleBringUpFailure(
            ManagedPlugin record, Throwable originalFailure, boolean pluginStarted) {
        Throwable cleanupFailure = quiesceFailedBringUpRuntime(record);
        if (cleanupFailure != null) {
            // Any retained runtime entry may still reach child code; do not stop the feature or close its context.
            lifecycleState.set(record.pluginId, PluginRuntimePhase.QUIESCED);
            if (isFatal(originalFailure)) {
                addSuppressedSafely(originalFailure, cleanupFailure);
                rethrowFatal(originalFailure);
            }
            if (isFatal(cleanupFailure)) {
                addSuppressedSafely(cleanupFailure, originalFailure);
                rethrowFatal(cleanupFailure);
            }
            addSuppressedSafely(cleanupFailure, originalFailure);
            if (cleanupFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cleanupFailure instanceof Error error) {
                throw error;
            }
            throw new PluginLifecycleException("runtime admission cleanup failed for plugin '"
                    + record.pluginId + "' (failureType=" + cleanupFailure.getClass().getName() + ")",
                    cleanupFailure);
        }
        try {
            rollBackBringUp(record, true, pluginStarted);
        } catch (Throwable failure) {
            cleanupFailure = mergeFailure(cleanupFailure, failure);
        }
        lifecycleState.set(record.pluginId, cleanupFailure == null
                ? PluginRuntimePhase.STOPPED : PluginRuntimePhase.QUIESCED);
        if (isFatal(originalFailure)) {
            addSuppressedSafely(originalFailure, cleanupFailure);
            rethrowFatal(originalFailure);
        }
        if (isFatal(cleanupFailure)) {
            addSuppressedSafely(cleanupFailure, originalFailure);
            rethrowFatal(cleanupFailure);
        }
        if (cleanupFailure != null) {
            if (cleanupFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new PluginLifecycleException("bring-up cleanup failed for plugin '"
                    + record.pluginId + "' (failureType=" + cleanupFailure.getClass().getName() + ")");
        }
    }

    /** 失败接入的清理：撤回本次接入的 web 贡献（幂等）。 */
    private void cleanUpFailedBringUp(ManagedPlugin record) {
        try {
            withdrawWebContribution(record, "rolling back");
        } catch (PluginLifecycleException failure) {
            lifecycleState.set(record.pluginId, PluginRuntimePhase.QUIESCED);
            throw failure;
        }
    }

    /**
     * 精确撤回当前 web serving。registrar 后段 best-effort cleanup 报错时句柄已经失效，清掉本地句柄并允许
     * STOPPED → start 重建；下载 publication 前置撤回失败时 registrar 会恢复同一句柄，此时保持 QUIESCED 并阻断 stop。
     */
    private void withdrawWebContribution(ManagedPlugin record, String action) {
        PluginWebContributionHandle handle = record.webHandle;
        if (handle == null) {
            return;
        }
        Throwable failure = null;
        try {
            webContributionRegistrar.unregister(handle);
        } catch (Throwable unregisterFailure) {
            failure = unregisterFailure;
        }
        boolean stillCurrent;
        try {
            stillCurrent = webContributionRegistrar.isCurrent(handle);
        } catch (Throwable inspectionFailure) {
            if (isFatal(failure)) {
                addSuppressedSafely(failure, inspectionFailure);
                rethrowFatal(failure);
            }
            if (isFatal(inspectionFailure)) {
                addSuppressedSafely(inspectionFailure, failure);
                rethrowFatal(inspectionFailure);
            }
            throw new PluginLifecycleException("cannot determine web serving withdrawal for plugin '"
                    + record.pluginId + "' (failureType=" + inspectionFailure.getClass().getName() + ")");
        }
        if (!stillCurrent) {
            record.webHandle = null;
            if (isFatal(failure)) {
                rethrowFatal(failure);
            }
            if (failure != null) {
                log.warn("Error {} web contributions for plugin '{}' after serving withdrawal (failureType={})",
                        action, record.pluginId, failure.getClass().getName());
            }
            return;
        }
        String failureType = failure == null ? "unknown" : failure.getClass().getName();
        if (isFatal(failure)) {
            rethrowFatal(failure);
        }
        throw new PluginLifecycleException("web serving remains current while " + action + " plugin '"
                + record.pluginId + "' (failureType=" + failureType + ")");
    }

    private void closeQuietly(ManagedPlugin record) {
        ConfigurableApplicationContext child = record.context;
        if (child == null && record.capabilityDrain == null) {
            return;
        }
        assertRuntimeDrained(record);
        if (!record.capabilityRetirementComplete || !record.capabilityRetirementAcknowledged) {
            throw new PluginLifecycleException("refusing to close child context before capability retirement: "
                    + record.pluginId);
        }
        if (!record.scheduleRetirementAcknowledged) {
            throw new PluginLifecycleException("refusing to close child context before schedule retirement: "
                    + record.pluginId);
        }
        if (child != null) {
            try {
                child.close();
            } catch (Throwable failure) {
                rethrowFatal(failure);
                throw new PluginLifecycleException("child context close remains pending for plugin '"
                        + record.pluginId + "' (failureType=" + failure.getClass().getName() + ")");
            }
        }
        if (record.capabilityDrain != null) {
            capabilityContributionRegistrar.releaseRetirementProof(record.capabilityDrain);
        }
        if (record.scheduleDrain != null) {
            scheduleContributionRegistrar.releaseRetirementProof(
                    scheduleMutationAuthority, record.scheduleDrain);
        }
        record.context = null;
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

    private Throwable unregisterAfterFailedRegistration(
            PluginRegistry.RegisteredPlugin registered, Throwable currentFailure) {
        try {
            if (pluginRegistry.containsIdentity(registered)) {
                pluginRegistry.unregister(registered);
            }
        } catch (Throwable cleanupFailure) {
            return mergeFailure(currentFailure, cleanupFailure);
        }
        return currentFailure;
    }

    private static void throwCompensatedLifecycleFailure(
            String message, Throwable originalFailure, Throwable cleanupFailure) {
        addSuppressedSafely(originalFailure, cleanupFailure);
        if (isFatal(originalFailure)) {
            rethrowFatal(originalFailure);
        }
        if (isFatal(cleanupFailure)) {
            addSuppressedSafely(cleanupFailure, originalFailure);
            rethrowFatal(cleanupFailure);
        }
        if (originalFailure instanceof Error error) {
            throw error;
        }
        throw new PluginLifecycleException(message + " (failureType="
                + originalFailure.getClass().getName() + ")", originalFailure);
    }

    private static void assertRuntimeDrained(ManagedPlugin record) {
        if (record.requestDrain != null && !record.requestDrain.isDrained()) {
            throw new PluginLifecycleException("refusing to close child context with active request leases: "
                    + record.pluginId + " (active=" + record.requestDrain.activeLeaseCount() + ")");
        }
        if (record.capabilityDrain != null && !record.capabilityDrain.isDrained()) {
            throw new PluginLifecycleException("refusing to close child context with active capability invocations: "
                    + record.pluginId + " (active=" + record.capabilityDrain.activeLeaseCount() + ")");
        }
        if (record.scheduleDrain != null && !record.scheduleDrain.isDrained()) {
            throw new PluginLifecycleException("refusing to close child context with active schedule leases: "
                    + record.pluginId + " (active=" + record.scheduleDrain.activeLeaseCount() + ")");
        }
        for (QueueDrain queueDrain : record.queueDrains) {
            if (!queueDrain.isDrained()) {
                throw new PluginLifecycleException("refusing to close child context with active queue tasks: "
                        + record.pluginId + "/" + queueDrain.queueType()
                        + " (active=" + queueDrain.activeCount() + ")");
            }
        }
    }

    /** 保留首个 fatal 对象身份；后续 fatal 仅作诊断，不覆盖主失败。 */
    private static Throwable mergeFatal(Throwable current, Throwable failure) {
        if (!isFatal(failure)) {
            return current;
        }
        if (current == null) {
            return failure;
        }
        addSuppressedSafely(current, failure);
        return current;
    }

    private static Throwable mergeFailure(Throwable current, Throwable failure) {
        if (failure == null) {
            return current;
        }
        if (current == null) {
            return failure;
        }
        if (!isFatal(current) && isFatal(failure)) {
            addSuppressedSafely(failure, current);
            return failure;
        }
        addSuppressedSafely(current, failure);
        return current;
    }

    private static void addSuppressedSafely(Throwable target, Throwable suppressed) {
        if (target == null || suppressed == null || target == suppressed) {
            return;
        }
        try {
            target.addSuppressed(suppressed);
        } catch (Throwable ignored) {
            // 诊断附加失败不得覆盖主异常。
        }
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

    private ManagedPlugin require(String pluginId) {
        ManagedPlugin record = managed.get(pluginId);
        if (record == null) {
            throw new PluginLifecycleException("unknown external plugin: " + pluginId);
        }
        return record;
    }

    /** 一个受管外置插件的可变运行期记录：标识、装配定义、注册条目、当前子 context 与精确 web serving 句柄。 */
    private static final class ManagedPlugin {
        final String pluginId;
        final long generation;
        final PluginContextModule module;                 // 可空（纯贡献插件无子 context）
        final PluginRegistry.RegisteredPlugin registered; // 可空（无核心注册条目的测试夹具）
        volatile ConfigurableApplicationContext context;  // 不在服务时为空
        PluginWebContributionHandle webHandle;
        PluginRequestGenerationDrain requestDrain;
        boolean requestWithdrawalComplete;
        PreparedOwner capabilityPreparation;
        ExternalCapabilityPublication capabilityPublication;
        ExternalCapabilityDrain capabilityDrain;
        boolean capabilityWithdrawalComplete;
        boolean capabilityRetirementComplete = true;
        boolean capabilityRetirementAcknowledged = true;
        ScheduleCapabilityPublication schedulePublication;
        ScheduleGenerationDrain scheduleDrain;
        boolean scheduleWithdrawalComplete;
        boolean scheduleRetirementAcknowledged = true;
        final List<QueueDrain> queueDrains = new ArrayList<>();
        boolean queuePreparationComplete;
        boolean runtimeTasksQuiesced;
        boolean controllerCleanupComplete = true;
        boolean capabilityCleanupComplete = true;

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
    }
}
