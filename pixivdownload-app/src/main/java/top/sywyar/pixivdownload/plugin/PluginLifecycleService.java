package top.sywyar.pixivdownload.plugin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;

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
 * 核心 {@link PluginRegistry}——并驱动状态机 {@link PluginLifecycleState}。{@link ExternalPluginContextManager}
 * （{@code SmartLifecycle}）在核心 context 刷新 / 关闭时调 {@link #startAll()} / {@link #stopAll()} 驱动启动期接入与关闭期收尾。
 *
 * <h2>停止顺序（与启动期接入对称）</h2>
 * {@code stop} 依次：<b>① 标记 {@link PluginRuntimePhase#QUIESCED}</b>（请求网关随即拒绝命中其路由的新请求）→
 * <b>② 停新计划任务派发</b>（{@link #shieldScheduleDispatch} 注销 schedule 贡献，与 HTTP 503 网关对称、排在清退在途之前，
 * 使 direct stop（from STARTED）的 drain 窗口内调度器也解析不到其来源 / 执行器、不再派发新一轮计划任务）→
 * <b>③ 清退在途运行期资源</b>（关闭 SSE 推流 + 排空在途下载队列）→ <b>④ 拆除服务足迹</b>
 * （{@link #tearDownServing}：注销 controller → 注销 schedule 贡献〔②已注销、此处为最终清退幂等 no-op〕→ 注销 web 贡献 →
 * 调插件 {@code stop()} → 关闭子 context）。每一步都被隔离（异常只记日志、不向上抛、不阻断后续步骤），且无论某步是否异常，
 * 阶段都强制落到 {@link PluginRuntimePhase#STOPPED}——保证「stop 中某一步异常不影响 registry 清退」。web 贡献注销复用
 * {@link PluginWebContributionRegistrar}（含 {@code ResourceBundle.clearCache} 与 {@code ScriptRegistry} 刷新）。
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
 * <h2>运行期任务清退（quiesce / 卸载时停新计划任务派发 + drain 在途 + 关闭 SSE）</h2>
 * {@code quiesce} 与 {@code stop} 在标记 {@link PluginRuntimePhase#QUIESCED} 后、拆除服务足迹前，先<b>停住该插件的
 * 新计划任务派发</b>、再清退其在途运行期资源（新 HTTP 请求已由 {@link PluginQuiesceGate} 在 HTTP 层 503 挡住）：
 * <ol>
 *   <li><b>停新计划任务派发</b>（{@link #shieldScheduleDispatch}）——经
 *       {@link PluginScheduleContributionRegistrar#unregister} 注销该插件 schedule 贡献（来源 + 作品类型执行器），使
 *       {@link ScheduledSourceRegistry} / {@code ScheduledWorkRunnerRegistry} 对其解析不到 → {@code ScheduleExecutor}
 *       把残留任务标 {@code SOURCE_UNAVAILABLE} 干净挂起（不发现 / 不派发、不删任务数据）。这与 HTTP 层 503 网关对称：
 *       HTTP 新请求被 {@link PluginQuiesceGate} 挡、计划任务新派发被来源注销挡。（{@code stop} 随后在
 *       {@link #tearDownServing} 再注销一次为幂等安全 no-op。）</li>
 *   <li><b>清退在途资源</b>（{@link #quiescePluginRuntimeTasks}）——① 经 {@link PluginStreamRegistry#closeForPlugin}
 *       关闭该插件全部活动 SSE 推流并向客户端发「插件不可用」事件；② 据插件 {@link PixivFeaturePlugin#queueTypes()}
 *       声明的每个 queueType 经核心队列宿主注册中心 {@link QueueOperationRegistry} 解析操作适配器并
 *       {@link top.sywyar.pixivdownload.core.download.queue.QueueOperations#clearAll() clearAll} 排空 / 取消其在途下载任务
 *       （不直依赖任一具体下载实现）。</li>
 * </ol>
 * 每步隔离（异常只记日志、不阻断后续清退）；某 queueType 操作缺席（贡献它的插件已禁 / 卸）时跳过、不报错。在途下载经
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

    private final ApplicationContext parent;
    private final PluginRuntimeManager pluginRuntimeManager;
    private final PluginApplicationContextFactory contextFactory;
    private final PluginControllerRegistrar controllerRegistrar;
    private final PluginWebContributionRegistrar webContributionRegistrar;
    private final PluginScheduleContributionRegistrar scheduleContributionRegistrar;
    private final PluginRegistry pluginRegistry;
    private final PluginLifecycleState lifecycleState;
    private final QueueOperationRegistry queueOperationRegistry;
    private final PluginStreamRegistry pluginStreamRegistry;

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
                                  PluginRegistry pluginRegistry,
                                  PluginLifecycleState lifecycleState,
                                  QueueOperationRegistry queueOperationRegistry,
                                  PluginStreamRegistry pluginStreamRegistry) {
        this.parent = parent;
        this.pluginRuntimeManager = pluginRuntimeManager;
        this.contextFactory = contextFactory;
        this.controllerRegistrar = controllerRegistrar;
        this.webContributionRegistrar = webContributionRegistrar;
        this.scheduleContributionRegistrar = scheduleContributionRegistrar;
        this.pluginRegistry = pluginRegistry;
        this.lifecycleState = lifecycleState;
        this.queueOperationRegistry = queueOperationRegistry;
        this.pluginStreamRegistry = pluginStreamRegistry;
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
            // 2) 没有子 context 的纯贡献外置插件：仅 web 贡献（构造期已接入），登记为 STARTED 以纳入热启停 / quiesce。
            for (PluginRegistry.RegisteredPlugin rp : external) {
                if (managed.containsKey(rp.id())) {
                    continue;
                }
                ManagedPlugin record = new ManagedPlugin(rp.id(), null, rp);
                record.webRegistered = true;
                managed.put(rp.id(), record);
                lifecycleState.initialize(rp.id(), PluginRuntimePhase.STARTED);
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
            for (String id : ids) {
                ManagedPlugin record = managed.get(id);
                PluginRuntimePhase phase = lifecycleState.phase(id).orElse(null);
                if (phase == PluginRuntimePhase.STARTED || phase == PluginRuntimePhase.QUIESCED) {
                    doStop(record);
                }
            }
            started = false;
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
        if (phase == PluginRuntimePhase.QUIESCED) {
            return; // 幂等：已静默
        }
        if (phase != PluginRuntimePhase.STARTED) {
            throw new PluginLifecycleException("cannot quiesce plugin '" + record.pluginId + "' from phase " + phase);
        }
        lifecycleState.transition(record.pluginId, PluginRuntimePhase.QUIESCED);
        // 停新计划任务派发（与 HTTP 层 503 网关对称）：注销该插件 schedule 贡献 → 调度器解析不到其来源 / 执行器 →
        // 新一轮 run 即 SOURCE_UNAVAILABLE 干净挂起（不发现 / 不派发、不删任务数据）。排在清退在途任务之前。
        shieldScheduleDispatch(record);
        quiescePluginRuntimeTasks(record);
        log.info("Quiesced plugin '{}': no longer accepting new requests.", record.pluginId);
    }

    private void doStop(ManagedPlugin record) {
        String pluginId = record.pluginId;
        PluginRuntimePhase phase = lifecycleState.phase(pluginId).orElse(null);
        if (phase == PluginRuntimePhase.STOPPED || phase == PluginRuntimePhase.UNLOADED
                || phase == PluginRuntimePhase.LOADED) {
            return; // 幂等 / 无服务足迹可拆
        }
        // 1) quiesce 标记（停止新入口）——若已 QUIESCED 则跳过（早返回后剩余只可能是 STARTED / QUIESCED）。
        if (phase == PluginRuntimePhase.STARTED) {
            lifecycleState.transition(pluginId, PluginRuntimePhase.QUIESCED);
        }
        // 2) 停新计划任务派发（与 HTTP 层 503 网关对称）：注销 schedule 贡献，排在清退在途任务之前——使 direct stop
        //    （from STARTED）也先停派发再 drain，drain 窗口内调度器解析不到其来源 / 执行器、不再派发新一轮 run。
        //    若已 QUIESCED（quiesce 已注销），此处为幂等安全 no-op；异常被隔离、不阻断下面的清退。
        shieldScheduleDispatch(record);
        // 3) 清退运行期任务：关闭 SSE 推流 + 排空在途下载队列（幂等：若 quiesce 已先清退，此处为安全空操作）。
        quiescePluginRuntimeTasks(record);
        // 4) 拆除服务足迹（每步隔离，异常不阻断后续清退；schedule 贡献在此再注销一次为最终清退幂等 no-op）。
        tearDownServing(record);
        // 5) 强制落到 STOPPED（即便上面某步异常也保证状态与清退一致）。
        lifecycleState.set(pluginId, PluginRuntimePhase.STOPPED);
        log.info("Stopped plugin '{}'.", pluginId);
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
                pluginRegistry.register(record.registered.plugin(), record.registered.source(),
                        record.registered.classLoader());
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
     * 建立服务足迹：（按需）接入 web 贡献 → 建子 context → 接入 schedule 贡献（来源 + 执行器）→ 动态注册 controller →
     *（仅运行期重启）调插件 {@code start()}，成功后阶段落到 {@link PluginRuntimePhase#STARTED}。任一步失败被隔离
     *（回滚本次已建立的足迹、阶段落到 STOPPED），不向上抛、不影响其它插件。
     *
     * <p>schedule 贡献排在子 context 建立<b>之后</b>（执行器是子 context 的 Bean，发现需先有子 context）、controller
     * 注册<b>之前</b>，经 {@link PluginScheduleContributionRegistrar} 接入插件的计划任务来源与作品类型执行器；纯贡献
     * 插件（无子 context）只接入来源。其失败与 controller / 运行期 {@code start()} 失败一样触发 {@link #rollBackBringUp}
     *（该回滚已含 schedule 贡献撤回）。
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
        // c) schedule 贡献：来源（来自插件，幂等）+ 执行器（从子 context 发现，纯贡献插件无子 context 则仅来源）。
        if (record.registered != null) {
            try {
                scheduleContributionRegistrar.register(record.registered, record.context);
            } catch (RuntimeException e) {
                log.error("Failed to register schedule contributions for plugin '{}': {} - rolling back service footprint.",
                        pluginId, e.toString(), e);
                rollBackBringUp(record);
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
                rollBackBringUp(record);
                lifecycleState.set(pluginId, PluginRuntimePhase.STOPPED);
                return;
            }
        }
        // e) 插件自身 start()：仅运行期重启路径调用（启动期由 PluginRegistry 统一调用、不在此重复）。失败即回滚本次足迹。
        if (invokePluginStart) {
            try {
                record.plugin().ifPresent(PixivFeaturePlugin::start);
            } catch (RuntimeException e) {
                log.error("Plugin '{}' start() failed: {} - rolling back its service footprint.",
                        pluginId, e.toString(), e);
                rollBackBringUp(record);
                lifecycleState.set(pluginId, PluginRuntimePhase.STOPPED);
                return;
            }
        }
        lifecycleState.transition(pluginId, PluginRuntimePhase.STARTED);
    }

    /**
     * 屏蔽该插件的计划任务派发：经 {@link PluginScheduleContributionRegistrar#unregister} 从来源 / 执行器注册中心
     * 注销其 schedule 贡献，使 {@link ScheduledSourceRegistry} / {@code ScheduledWorkRunnerRegistry} 对其来源 / 作品类型
     * 解析不到——{@code ScheduleExecutor} 据此把残留任务标 {@code SOURCE_UNAVAILABLE} 干净挂起（清 {@code run_started_time}、
     * 不发现 / 不派发、不冻账号 / 不发通知，任务 params / watermark / pending 零删除），来源恢复后可再被 {@code findDue} 调度。
     * 由 {@code quiesce} 与 {@code stop} 在标记 {@link PluginRuntimePhase#QUIESCED} 后、清退在途任务
     * （{@link #quiescePluginRuntimeTasks}）前调用，使「计划任务新派发」随 quiesce / stop 一并停住——与 HTTP 层
     * {@link PluginQuiesceGate} 的 503 网关对称（二者都不靠 {@code ScheduleExecutor} / {@code AuthFilter} 读生命周期阶段，
     * 而是分别经来源注销 / 路由属主判定停住新入口）。隔离异常（只记日志、不阻断后续清退）。
     * {@link PluginScheduleContributionRegistrar#unregister} 幂等：无论 direct stop（自身 shield + {@link #tearDownServing}
     * 再注销）还是 quiesce 后 stop（quiesce + stop 各注销），重复注销均为安全 no-op、不重复出错。
     */
    private void shieldScheduleDispatch(ManagedPlugin record) {
        try {
            scheduleContributionRegistrar.unregister(record.pluginId);
        } catch (RuntimeException e) {
            log.warn("Error unregistering schedule contributions for plugin '{}' during quiesce: {}",
                    record.pluginId, e.toString());
        }
    }

    /**
     * 清退该插件的运行期在途任务（在 quiesce / stop 标记 {@link PluginRuntimePhase#QUIESCED} 后、拆除服务足迹前
     * 调用）：先关闭其全部 SSE 推流（客户端收到「插件不可用」事件），再排空 / 取消其各 queueType 的在途下载任务。
     * 每步隔离（异常只记日志、不阻断后续）；幂等（quiesce 已清退后 stop 再调为安全空操作）。
     */
    private void quiescePluginRuntimeTasks(ManagedPlugin record) {
        String pluginId = record.pluginId;
        try {
            int closed = pluginStreamRegistry.closeForPlugin(pluginId);
            if (closed > 0) {
                log.info("Closed {} server-push stream(s) for plugin '{}'.", closed, pluginId);
            }
        } catch (RuntimeException e) {
            log.warn("Error closing server-push streams for plugin '{}': {}", pluginId, e.toString());
        }
        drainQueueTasks(record);
    }

    /**
     * 按插件声明的 {@link PixivFeaturePlugin#queueTypes() queueTypes} 经核心队列宿主注册中心
     * {@link QueueOperationRegistry} 排空 / 取消其在途下载任务（{@code clearAll}）；不直依赖任一具体下载实现。
     * 某 queueType 操作缺席（贡献它的插件已禁 / 卸）时跳过；逐个隔离异常。
     */
    private void drainQueueTasks(ManagedPlugin record) {
        Optional<PixivFeaturePlugin> plugin = record.plugin();
        if (plugin.isEmpty()) {
            return;
        }
        List<QueueTypeContribution> queueTypes;
        try {
            queueTypes = plugin.get().queueTypes();
        } catch (RuntimeException e) {
            log.warn("Error reading queue types for plugin '{}': {}", record.pluginId, e.toString());
            return;
        }
        for (QueueTypeContribution queueType : queueTypes) {
            String type = queueType.type();
            try {
                queueOperationRegistry.resolve(type).ifPresent(ops -> {
                    int drained = ops.clearAll();
                    if (drained > 0) {
                        log.info("Drained {} in-flight task(s) of queue type '{}' for plugin '{}'.",
                                drained, type, record.pluginId);
                    }
                });
            } catch (RuntimeException e) {
                log.warn("Error draining queue type '{}' for plugin '{}': {}",
                        type, record.pluginId, e.toString());
            }
        }
    }

    /**
     * 拆除服务足迹，顺序为 <b>注销 controller → 注销 schedule 贡献 → 注销 web 贡献 → 调插件 {@code stop()} →
     * 关闭子 context</b>。每一步被隔离（异常只记日志、不抛出），保证某步异常不阻断后续清退。schedule 贡献注销
     * 经 {@link PluginScheduleContributionRegistrar}（注销来源 + 按已记录 {@code kind} 注销执行器）；注销后调度器
     * 对该插件来源 / 作品类型解析不到 → 残留任务进入 {@code SOURCE_UNAVAILABLE} 干净挂起、数据不删（既有语义）。
     */
    private void tearDownServing(ManagedPlugin record) {
        String pluginId = record.pluginId;
        try {
            controllerRegistrar.unregisterControllers(pluginId);
        } catch (RuntimeException e) {
            log.warn("Error unregistering controllers for plugin '{}': {}", pluginId, e.toString());
        }
        try {
            scheduleContributionRegistrar.unregister(pluginId);
        } catch (RuntimeException e) {
            log.warn("Error unregistering schedule contributions for plugin '{}': {}", pluginId, e.toString());
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
     * 回滚本次 {@link #bringUpServing} 已建立的服务足迹：<b>注销 controller → 注销 schedule 贡献 → 关闭子 context →
     * 撤回 web 贡献</b>（bring-up 顺序的逆序）。用于 schedule 贡献注册失败 / controller 注册失败 / 插件 {@code start()}
     * 失败。各步幂等（对未接入项为安全 no-op，故同一回滚助手覆盖三种失败点）。<b>不</b>调用插件 {@code stop()}——
     * {@code start()} 未成功、插件未进入运行态，按 footprint 维度回退即可；每步隔离、异常只记日志。
     */
    private void rollBackBringUp(ManagedPlugin record) {
        try {
            controllerRegistrar.unregisterControllers(record.pluginId);
        } catch (RuntimeException e) {
            log.warn("Error rolling back controllers for plugin '{}': {}", record.pluginId, e.toString());
        }
        try {
            scheduleContributionRegistrar.unregister(record.pluginId);
        } catch (RuntimeException e) {
            log.warn("Error rolling back schedule contributions for plugin '{}': {}", record.pluginId, e.toString());
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
        final PluginContextModule module;                 // 可空（纯贡献插件无子 context）
        final PluginRegistry.RegisteredPlugin registered; // 可空（无核心注册条目的测试夹具）
        volatile ConfigurableApplicationContext context;  // 不在服务时为空
        boolean webRegistered;

        ManagedPlugin(String pluginId, PluginContextModule module, PluginRegistry.RegisteredPlugin registered) {
            this.pluginId = pluginId;
            this.module = module;
            this.registered = registered;
        }

        Optional<PixivFeaturePlugin> plugin() {
            return registered == null ? Optional.empty() : Optional.of(registered.plugin());
        }
    }
}
