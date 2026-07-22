package top.sywyar.pixivdownload.plugin.runtime.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginDirectorySessionLock;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.Failure;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.FailureKind;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.LoadedPluginPackage;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 进程级插件运行时 bootstrap 会话：把「恢复待处理安装事务 → 构造唯一 {@link PluginRuntimeManager} → 一次启动扫描与
 * start」收口为一处，并持有唯一的 {@link ExternalPluginInstaller} / 运行时管理器 / 启动状态 / 启用快照 / 所有权语义。
 *
 * <p>两条启动路径共用同一会话类型，以 ownership 区分关闭责任：
 * <ul>
 *   <li>{@link Ownership#PROCESS}：GUI 进程在 Spring 启动<b>前</b>创建并拥有会话。Spring context 关闭（后端 stop / restart）
 *       <b>不得</b>关闭它；进程最终退出时由 GUI 关闭。后端 restart 复用同一管理器 / 插件实例 / classloader。</li>
 *   <li>{@link Ownership#CONTEXT}：headless / Spring 自行创建并拥有会话。context 关闭时<b>必须</b>释放运行时。</li>
 * </ul>
 *
 * <p>本类是 plugin-runtime bootstrap 的中性载体：<b>不</b> import Spring / Swing / FlatLaf / JNA / RuntimeFiles /
 * ConfigFileEditor / app 业务包；启用快照由调用方传入（{@link PluginEnabledSnapshot}，纯 JDK）。PF4J 完全收口在
 * {@link PluginRuntimeManager} 内、本类不暴露任何 PF4J 类型，故 app 持有本会话不会接触 PF4J。
 *
 * <p>启动顺序固定且只执行一次：{@code recoverPendingTransactions()}（早于扫描）→ 建立恢复结果安全门 → 构造受门禁
 * manager → 仅在安全时执行 {@code manager.start()}（一次扫描 + start）→ 保存 status → 一次启动期 discovery（保存不可变 startup inventory /
 * discovery 快照）。任何未安全处理的事务都会结构化收敛为 status / 诊断并<b>阻止本次 PF4J 扫描</b>；任一事务的
 * claims 无法证明时整轮恢复在写入前停止，避免未知事务与其它事务共享目标。Spring 接手后<b>不得</b>再次恢复或 start。插件目录缺失 / 空 / 坏包 / API 不兼容 / provider 抛错
 * 等其余失败仍收敛为 status / 诊断，不阻断 GUI 进入系统 LookAndFeel。普通 {@link Error} 与运行时异常一样隔离；
 * {@link VirtualMachineError} / {@link ThreadDeath} 会先关闭半初始化 manager 与目录 lease，再保持原对象身份重抛，
 * 关闭后的会话不可复活。
 * <p>启动期 inventory / discovery 快照在 {@code start()} 内一次性产出、不可变，供 Spring 刷新前的消费者读取；
 * 运行期当前 inventory / discovery 仍由 {@link PluginRuntimeConfiguration} 的 prototype Bean 从同一 manager 动态清点
 *（后端 restart 后反映装卸后的当前状态），二者不得互相替代。快照持有插件实例 / classloader 引用，<b>不得</b>无限期留在
 * 进程会话——它在启动前的短生命周期窗口内供消费者读取，消费完（或确认无消费者）后必须经 {@link #releaseStartupSnapshot()}
 * 显式释放，{@link #close()} 也会无条件清空；释放后运行期 reload / 卸载的旧 generation classloader 不再被快照钉住。
 * 会话 {@link #close()} 后不可重新 {@code start()}。
 */
public final class PluginBootstrapSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PluginBootstrapSession.class);

    private final Path pluginsRoot;
    private final Ownership ownership;
    private final PluginEnabledSnapshot enabledSnapshot;
    private final ExternalPluginInstaller installer;
    private final RuntimeManagerFactory runtimeManagerFactory;
    private final RecoveryOperation recoveryOperation;
    private final List<String> diagnostics = new ArrayList<>();

    private volatile Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver;
    private volatile PluginRuntimeManager manager;
    private volatile PluginRuntimeStatus status;
    private volatile PluginInventory startupInventory = PluginInventory.empty();
    private volatile PluginDiscoveryResult startupDiscovery = PluginDiscoveryResult.empty();
    private volatile boolean started;
    private volatile boolean closed;

    private PluginBootstrapSession(Path pluginsRoot, Ownership ownership, PluginEnabledSnapshot enabledSnapshot,
                                   PluginSupplyChainVerifier verifier) {
        this(pluginsRoot, ownership, enabledSnapshot, fixedVerifier(verifier));
    }

    private PluginBootstrapSession(Path pluginsRoot, Ownership ownership, PluginEnabledSnapshot enabledSnapshot,
                                   Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver) {
        this(pluginsRoot, ownership, enabledSnapshot, verifierResolver,
                RecoveryGatedPluginRuntimeManager::new);
    }

    PluginBootstrapSession(Path pluginsRoot, Ownership ownership, PluginEnabledSnapshot enabledSnapshot,
                           Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver,
                           RuntimeManagerFactory runtimeManagerFactory) {
        this(pluginsRoot, ownership, enabledSnapshot, verifierResolver, runtimeManagerFactory,
                ExternalPluginInstaller::recoverPendingTransactions);
    }

    PluginBootstrapSession(Path pluginsRoot, Ownership ownership, PluginEnabledSnapshot enabledSnapshot,
                           Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver,
                           RuntimeManagerFactory runtimeManagerFactory,
                           RecoveryOperation recoveryOperation) {
        this.pluginsRoot = Objects.requireNonNull(pluginsRoot, "pluginsRoot").toAbsolutePath().normalize();
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.enabledSnapshot = Objects.requireNonNull(enabledSnapshot, "enabledSnapshot");
        Function<PluginPackageOrigin, PluginSupplyChainVerifier> effectiveResolver =
                Objects.requireNonNull(verifierResolver, "verifierResolver");
        this.verifierResolver = effectiveResolver;
        this.runtimeManagerFactory = Objects.requireNonNull(runtimeManagerFactory, "runtimeManagerFactory");
        this.recoveryOperation = Objects.requireNonNull(recoveryOperation, "recoveryOperation");
        PluginDirectorySessionLock directoryLock = new PluginDirectorySessionLock(this.pluginsRoot);
        this.installer = new ExternalPluginInstaller(this.pluginsRoot,
                PluginPackageLimits.defaults(),
                effectiveResolver, directoryLock);
    }

    /** GUI 进程拥有的会话（Spring 关闭不释放、进程退出时关闭）。 */
    public static PluginBootstrapSession createProcess(Path pluginsRoot, PluginEnabledSnapshot enabledSnapshot) {
        return createProcess(pluginsRoot, enabledSnapshot, new PluginSupplyChainVerifier());
    }

    /** GUI 进程拥有的会话（Spring 关闭不释放、进程退出时关闭），使用调用方提供的验签门面。 */
    public static PluginBootstrapSession createProcess(Path pluginsRoot, PluginEnabledSnapshot enabledSnapshot,
                                                       PluginSupplyChainVerifier verifier) {
        return new PluginBootstrapSession(pluginsRoot, Ownership.PROCESS, enabledSnapshot, verifier);
    }

    /** GUI 进程拥有的会话（Spring 关闭不释放、进程退出时关闭），使用调用方提供的按来源验签门面。 */
    public static PluginBootstrapSession createProcess(Path pluginsRoot, PluginEnabledSnapshot enabledSnapshot,
                                                       Function<PluginPackageOrigin, PluginSupplyChainVerifier>
                                                               verifierResolver) {
        return new PluginBootstrapSession(pluginsRoot, Ownership.PROCESS, enabledSnapshot, verifierResolver);
    }

    /** headless / Spring context 拥有的会话（context 关闭时释放）。 */
    public static PluginBootstrapSession createContext(Path pluginsRoot, PluginEnabledSnapshot enabledSnapshot) {
        return createContext(pluginsRoot, enabledSnapshot, new PluginSupplyChainVerifier());
    }

    /** headless / Spring context 拥有的会话（context 关闭时释放），使用调用方提供的验签门面。 */
    public static PluginBootstrapSession createContext(Path pluginsRoot, PluginEnabledSnapshot enabledSnapshot,
                                                       PluginSupplyChainVerifier verifier) {
        return new PluginBootstrapSession(pluginsRoot, Ownership.CONTEXT, enabledSnapshot, verifier);
    }

    /** headless / Spring context 拥有的会话（context 关闭时释放），使用调用方提供的按来源验签门面。 */
    public static PluginBootstrapSession createContext(Path pluginsRoot, PluginEnabledSnapshot enabledSnapshot,
                                                       Function<PluginPackageOrigin, PluginSupplyChainVerifier>
                                                               verifierResolver) {
        return new PluginBootstrapSession(pluginsRoot, Ownership.CONTEXT, enabledSnapshot, verifierResolver);
    }

    /**
     * 执行一次启动扫描：先逐事务恢复待处理安装事务（早于扫描）；恢复报告安全才进入 {@code manager.start()}，否则产出
     * fail-closed status / 空 discovery 并结束本次启动。幂等——多次调用只启动一次，Spring 接手后再次调用是 no-op。
     * {@link #close()} 后再调用显式拒绝（抛 {@link IllegalStateException}，不可复活）。非致命失败只记诊断、不向调用方抛出；
     * discovery 失败也只记诊断、不阻断已安全完成的 runtime start。JVM 致命错误会先关闭 manager 与目录 lease，再按原对象
     * 身份重抛；该会话随后保持 closed，不能重试半初始化状态。
     */
    public synchronized PluginBootstrapSession start() {
        if (closed) {
            throw new IllegalStateException("PluginBootstrapSession has been closed and cannot be restarted");
        }
        if (started) {
            return this;
        }
        PluginTransactionRecoveryReport recoveryReport;
        try {
            recoveryReport = recoveryOperation.recover(installer);
        } catch (Throwable e) {
            if (isFatal(e)) {
                abortAfterFatalStartupFailure(null, e);
                rethrowFatal(e);
            }
            recoveryReport = new PluginTransactionRecoveryReport(List.of(new Failure(
                    "<recovery>", pluginsRoot.resolve(".staging"), FailureKind.RECOVERY_FAILED, describe(e))));
            installer.blockRuntimeOperations(recoveryReport);
            log.warn("Plugin install transaction recovery failed unexpectedly: {}", describe(e), e);
            rethrowFatal(e);
        }
        PluginRuntimeManager runtimeManager;
        try {
            runtimeManager = createManagerAfterRecovery();
        } catch (Throwable e) {
            this.status = new PluginRuntimeStatus(pluginsRoot, PluginDirectoryState.ABSENT,
                    List.of(), List.of(), List.of(new PluginLoadFailure(pluginsRoot.toString(),
                    "plugin runtime manager construction failed: " + describe(e))));
            diagnostics.add("plugin runtime manager construction failed: " + describe(e));
            log.warn("Plugin runtime manager construction failed: {}", describe(e), e);
            if (isFatal(e)) {
                abortAfterFatalStartupFailure(null, e);
                rethrowFatal(e);
            }
            this.started = true;
            return this;
        }
        if (!recoveryReport.safeToScan()) {
            blockScanForRecoveryFailures(recoveryReport);
            this.started = true;
            return this;
        }
        try {
            this.status = runtimeManager.start();
        } catch (Throwable e) {
            this.status = new PluginRuntimeStatus(pluginsRoot, PluginDirectoryState.ABSENT,
                    List.of(), List.of(), List.of(new PluginLoadFailure(pluginsRoot.toString(),
                    "plugin runtime start failed: " + describe(e))));
            diagnostics.add("plugin runtime start failed: " + describe(e));
            log.warn("Plugin runtime start failed: {}", describe(e), e);
            if (isFatal(e)) {
                abortAfterFatalStartupFailure(runtimeManager, e);
                rethrowFatal(e);
            }
        }
        runStartupDiscovery(runtimeManager);
        this.started = true;
        return this;
    }

    private void blockScanForRecoveryFailures(PluginTransactionRecoveryReport recoveryReport) {
        List<PluginLoadFailure> failures = new ArrayList<>();
        for (Failure failure : recoveryReport.failures()) {
            String reason = "plugin transaction recovery failed [" + failure.kind() + "]"
                    + " transaction=" + failure.transactionId() + ": " + failure.detail();
            diagnostics.add(reason + " path=" + failure.transactionDirectory());
            failures.add(new PluginLoadFailure(failure.transactionDirectory().toString(), reason));
            log.error("{} path={}", reason, failure.transactionDirectory());
        }
        // 恢复失败后禁止为补状态再次触碰插件根；目录状态保守投影，结构化 report 才是权威事实。
        this.status = new PluginRuntimeStatus(pluginsRoot, PluginDirectoryState.POPULATED,
                List.of(), List.of(), failures);
        this.startupInventory = PluginInventory.empty();
        this.startupDiscovery = PluginDiscoveryResult.empty();
    }

    /**
     * 启动期 discovery：从同一 manager 做一次清点（{@code inspectPlugins}）并据此投影 discovery
     *（{@code toDiscoveryResult}），二者共用同一次 provider 调用、不重复清点。失败收敛为空 inventory / discovery + 诊断，
     * 不阻断 start。在 {@code start()} 内调用一次；幂等 start 下不会再次执行。
     */
    private void runStartupDiscovery(PluginRuntimeManager runtimeManager) {
        try {
            PluginInventory inventory = runtimeManager.inspectPlugins();
            this.startupInventory = inventory;
            this.startupDiscovery = runtimeManager.toDiscoveryResult(inventory);
        } catch (Throwable e) {
            this.startupInventory = PluginInventory.empty();
            this.startupDiscovery = PluginDiscoveryResult.empty();
            diagnostics.add("startup plugin discovery failed: " + describe(e));
            log.warn("Startup plugin discovery failed: {}", describe(e), e);
            if (isFatal(e)) {
                abortAfterFatalStartupFailure(runtimeManager, e);
                rethrowFatal(e);
            }
        }
    }

    /** fatal 启动失败后使整个会话不可复活，不能在下一次 start() 复用半初始化 manager。 */
    private void abortAfterFatalStartupFailure(PluginRuntimeManager runtimeManager, Throwable fatal) {
        closed = true;
        started = false;
        startupInventory = PluginInventory.empty();
        startupDiscovery = PluginDiscoveryResult.empty();
        manager = null;
        if (runtimeManager != null) {
            try {
                runtimeManager.shutdown();
            } catch (Throwable cleanupFailure) {
                addSuppressedSafely(fatal, cleanupFailure);
            }
        }
        try {
            installer.close();
        } catch (Throwable closeFailure) {
            addSuppressedSafely(fatal, closeFailure);
        }
    }

    /**
     * 返回恢复结论建立后构造的受门禁 manager。start 前尚无恢复结论，因此不可取得；恢复结论为 BLOCKED 时仍返回同一
     * inert manager，供 Spring 完成核心装配，但其扫描、加载与启动操作会被恢复安全门拒绝。
     */
    public PluginRuntimeManager manager() {
        PluginRuntimeManager current = manager;
        if (current == null) {
            throw new IllegalStateException("PluginRuntimeManager is unavailable until start() establishes the "
                    + "transaction recovery decision");
        }
        return current;
    }

    public ExternalPluginInstaller installer() {
        return installer;
    }

    /**
     * 宿主解析仓库配置后同步刷新后续安装与单包加载共用的验签门面。
     * <p>已经完成的启动扫描不在这里重跑：PROCESS 会话由 GUI 在 Spring 前启动，后端 context 刷新 / restart 只应复用
     * 已验证 generation 和 classloader，不能因为 trust store Bean 刷新而再次 PF4J load/start。需要加载新增或此前隔离的
     * 包时，应走显式安装 / 生命周期动作或完整进程重启。
     */
    public synchronized void updateVerifier(PluginSupplyChainVerifier verifier) {
        updateVerifierResolver(fixedVerifier(verifier));
    }

    /** 宿主解析仓库配置后同步刷新后续安装与单包加载共用的按来源验签门面。 */
    public synchronized void updateVerifierResolver(
            Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver) {
        Function<PluginPackageOrigin, PluginSupplyChainVerifier> effectiveResolver =
                Objects.requireNonNull(verifierResolver, "verifierResolver");
        installer.updateVerifierResolver(effectiveResolver);
        this.verifierResolver = effectiveResolver;
        PluginRuntimeManager current = manager;
        if (current != null) {
            current.updateVerifierResolver(effectiveResolver);
        }
    }

    public PluginEnabledSnapshot enabledSnapshot() {
        return enabledSnapshot;
    }

    public Ownership ownership() {
        return ownership;
    }

    public Path pluginsRoot() {
        return pluginsRoot;
    }

    /** 启动扫描产出的 status；尚未 start 时返回一个 ABSENT 占位 status（不抛、不返回 null）。 */
    public PluginRuntimeStatus status() {
        PluginRuntimeStatus current = status;
        if (current != null) {
            return current;
        }
        return new PluginRuntimeStatus(pluginsRoot, PluginDirectoryState.ABSENT, List.of(), List.of(), List.of());
    }

    /**
     * 启动期一次清点产出的不可变 inventory 快照（在 {@code start()} 内、manager.start 后一次性产出）。供 Spring 刷新前的
     * 消费者读取；运行期当前 inventory 仍从 {@link #manager()} 动态清点。尚未 start 时返回空 inventory。
     *
     * <p>该快照持有插件实例 / classloader 引用，<b>仅</b>存在于启动前的短生命周期窗口；消费完应经
     * {@link #releaseStartupSnapshot()} 释放，避免钉住运行期 reload / 卸载后的旧 generation classloader。
     */
    public PluginInventory startupInventory() {
        return startupInventory;
    }

    /**
     * 启动期一次清点投影出的不可变 discovery 快照（与 {@link #startupInventory()} 同一次 provider 调用）。供 Spring 刷新前的
     * 消费者读取；运行期当前 discovery 仍从 {@link #manager()} 动态清点。尚未 start 时返回空 discovery。
     *
     * <p>同 {@link #startupInventory()}：持有插件实例 / classloader 引用，消费完应经 {@link #releaseStartupSnapshot()} 释放。
     */
    public PluginDiscoveryResult startupDiscovery() {
        return startupDiscovery;
    }

    /**
     * 显式释放启动期 inventory / discovery 快照（置为空实例）。快照持有插件实例 / classloader 引用，<b>不得</b>无限期留在
     * 进程会话——它在启动前的短生命周期窗口内供消费者读取，消费完（或确认无消费者，如当前尚无主题消费者）后必须释放，
     * 使运行期 reload / 卸载的旧 generation classloader 不被快照钉住。幂等、多次调用安全；{@link #close()} 也会无条件清空。
     * 释放后 {@link #startupInventory()} / {@link #startupDiscovery()} 返回空实例；运行期当前清点仍可从 {@link #manager()} 取得。
     */
    public synchronized void releaseStartupSnapshot() {
        startupInventory = PluginInventory.empty();
        startupDiscovery = PluginDiscoveryResult.empty();
    }

    public List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public boolean isStarted() {
        return started;
    }

    /** 进程级强制关闭（{@link #close()}）是否已执行过；用于观测所有权语义（PROCESS 经 context 关闭后仍为 false、CONTEXT 为 true）。 */
    public boolean isClosed() {
        return closed;
    }

    /**
     * 只关闭 {@link Ownership#CONTEXT} 拥有的会话；{@link Ownership#PROCESS} 拥有者为 no-op（进程退出时另由
     * {@link #close()} 关闭）。供 Spring 的 bean destroy 回调调用——同一回调对两种 ownership 都安全。
     */
    public synchronized void closeForContext() {
        if (ownership == Ownership.CONTEXT) {
            doClose();
        }
    }

    /** 进程最终退出的强制、幂等关闭：停止 / 卸载全部插件、释放 classloader / 文件句柄。 */
    @Override
    public synchronized void close() {
        doClose();
    }

    private void doClose() {
        if (closed) {
            return;
        }
        closed = true;
        // 无条件清空启动期快照——它持有插件实例 / classloader 引用，关闭时不得残留钉住旧 generation。
        startupInventory = PluginInventory.empty();
        startupDiscovery = PluginDiscoveryResult.empty();
        PluginRuntimeManager current = manager;
        try {
            if (current != null) {
                current.shutdown();
            }
        } catch (RuntimeException e) {
            diagnostics.add("plugin runtime shutdown failed: " + describe(e));
            log.warn("Plugin runtime shutdown failed: {}", describe(e), e);
        } finally {
            try {
                installer.close();
            } catch (RuntimeException e) {
                diagnostics.add("plugin directory lock release failed: " + describe(e));
                log.warn("Plugin directory lock release failed: {}", describe(e), e);
            }
        }
    }

    /**
     * 仅由同步的 start() 在 recoverPendingTransactions() 返回或收敛失败报告后调用；构造本身不触碰插件目录，后续
     * 扫描、加载与启动是否允许均由恢复安全门决定。
     */
    private PluginRuntimeManager createManagerAfterRecovery() {
        PluginRuntimeManager current = manager;
        if (current == null) {
            current = runtimeManagerFactory.create(pluginsRoot, verifierResolver, installer);
            manager = current;
        }
        return current;
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getName() : error.getMessage();
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError virtualMachineError) {
            throw virtualMachineError;
        }
        if (failure instanceof ThreadDeath threadDeath) {
            throw threadDeath;
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void addSuppressedSafely(Throwable primary, Throwable suppressed) {
        if (primary == null || suppressed == null || primary == suppressed) {
            return;
        }
        try {
            primary.addSuppressed(suppressed);
        } catch (Throwable ignored) {
            // 保留原 fatal 对象身份。
        }
    }

    private static Function<PluginPackageOrigin, PluginSupplyChainVerifier> fixedVerifier(
            PluginSupplyChainVerifier verifier) {
        PluginSupplyChainVerifier fixed = Objects.requireNonNull(verifier, "verifier");
        return origin -> fixed;
    }

    @FunctionalInterface
    interface RuntimeManagerFactory {
        PluginRuntimeManager create(
                Path pluginsRoot,
                Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver,
                ExternalPluginInstaller installer);
    }

    @FunctionalInterface
    interface RecoveryOperation {
        PluginTransactionRecoveryReport recover(ExternalPluginInstaller installer);
    }

    /**
     * 会话内 manager 的进程级恢复安全门。放在 plugin-runtime 内部，避免 manager 或 plugin-api 反向依赖 bootstrap/app；
     * 一旦 installer 固化不安全报告，bootstrap 重入及后续显式 load/start 均 fail-closed。
     */
    private static final class RecoveryGatedPluginRuntimeManager extends PluginRuntimeManager {

        private final ExternalPluginInstaller installer;

        private RecoveryGatedPluginRuntimeManager(
                Path pluginsRoot,
                Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver,
                ExternalPluginInstaller installer) {
            super(pluginsRoot, verifierResolver);
            this.installer = Objects.requireNonNull(installer, "installer");
        }

        @Override
        public synchronized PluginRuntimeStatus start() {
            requireRecoverySafe("start plugin runtime");
            return super.start();
        }

        @Override
        protected void beforeProductionScan(Path directory) throws java.io.IOException {
            try {
                installer.prepareRuntimeScan();
            } catch (IllegalStateException e) {
                throw new java.io.IOException("plugin directory is not safe to scan", e);
            }
        }

        @Override
        public synchronized LoadedPluginPackage loadPlugin(Path artifactPath) {
            requireRecoverySafe("load plugin package");
            return super.loadPlugin(artifactPath);
        }

        @Override
        public synchronized LoadedPluginPackage startPlugin(String packageId) {
            prepareRuntimeEntryStart();
            requireRecoverySafe("start plugin package");
            return super.startPlugin(packageId);
        }

        private void prepareRuntimeEntryStart() {
            try {
                installer.prepareRuntimeScan();
            } catch (IllegalStateException e) {
                throw new IllegalStateException("plugin directory is not safe to start", e);
            }
        }

        private void requireRecoverySafe(String operation) {
            if (!installer.recoverySafeForRuntime()) {
                throw new IllegalStateException(
                        "plugin transaction recovery is unsafe; refusing to " + operation
                                + " until the process is restarted after recovery");
            }
        }
    }

    /** 会话所有权。 */
    public enum Ownership {
        /** GUI 进程拥有：Spring context 关闭不释放，进程退出时关闭；后端 restart 复用同一运行时。 */
        PROCESS,
        /** headless / Spring context 拥有：context 关闭时释放运行时。 */
        CONTEXT
    }
}
