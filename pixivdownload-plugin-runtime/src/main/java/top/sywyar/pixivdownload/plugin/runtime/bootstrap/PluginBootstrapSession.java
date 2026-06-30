package top.sywyar.pixivdownload.plugin.runtime.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.runtime.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.PluginLoadFailure;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 * <p>启动顺序固定且只执行一次：{@code recoverPendingTransactions()}（best-effort、早于扫描）→ 构造 manager →
 * {@code manager.start()}（一次扫描 + start）→ 保存 status → 一次启动期 discovery（保存不可变 startup inventory /
 * discovery 快照）。Spring 接手后<b>不得</b>再次恢复或 start。失败收敛：插件目录缺失 / 空 / 坏包 / API 不兼容 /
 * provider 抛错 / 安装事务恢复失败均收敛为 status / 诊断，不抛异常、不阻断 GUI 进入系统 LookAndFeel，也不吞掉 JVM
 * 致命 Error（不捕获 {@link Error}）。
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
    private final PluginRuntimeManager manager;
    private final List<String> diagnostics = new ArrayList<>();

    private volatile PluginRuntimeStatus status;
    private volatile PluginInventory startupInventory = PluginInventory.empty();
    private volatile PluginDiscoveryResult startupDiscovery = PluginDiscoveryResult.empty();
    private volatile boolean started;
    private volatile boolean closed;

    private PluginBootstrapSession(Path pluginsRoot, Ownership ownership, PluginEnabledSnapshot enabledSnapshot) {
        this.pluginsRoot = Objects.requireNonNull(pluginsRoot, "pluginsRoot").toAbsolutePath().normalize();
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.enabledSnapshot = Objects.requireNonNull(enabledSnapshot, "enabledSnapshot");
        this.installer = new ExternalPluginInstaller(this.pluginsRoot);
        this.manager = new PluginRuntimeManager(this.pluginsRoot);
    }

    /** GUI 进程拥有的会话（Spring 关闭不释放、进程退出时关闭）。 */
    public static PluginBootstrapSession createProcess(Path pluginsRoot, PluginEnabledSnapshot enabledSnapshot) {
        return new PluginBootstrapSession(pluginsRoot, Ownership.PROCESS, enabledSnapshot);
    }

    /** headless / Spring context 拥有的会话（context 关闭时释放）。 */
    public static PluginBootstrapSession createContext(Path pluginsRoot, PluginEnabledSnapshot enabledSnapshot) {
        return new PluginBootstrapSession(pluginsRoot, Ownership.CONTEXT, enabledSnapshot);
    }

    /**
     * 执行一次启动扫描：先恢复待处理安装事务（best-effort、早于扫描），再 {@code manager.start()}，最后做一次启动期
     * discovery 并保存不可变 startup inventory / discovery 快照。幂等——多次调用只启动一次，Spring 接手后再次调用是
     * no-op。{@link #close()} 后再调用显式拒绝（抛 {@link IllegalStateException}，不可复活）。任意步骤失败只记诊断、
     * 产出降级 status / 空 discovery，不抛异常（discovery 失败也只记诊断、不阻断）。
     */
    public synchronized PluginBootstrapSession start() {
        if (closed) {
            throw new IllegalStateException("PluginBootstrapSession has been closed and cannot be restarted");
        }
        if (started) {
            return this;
        }
        try {
            installer.recoverPendingTransactions();
        } catch (RuntimeException e) {
            diagnostics.add("plugin install transaction recovery failed: " + describe(e));
            log.warn("Plugin install transaction recovery failed: {}", describe(e), e);
        }
        try {
            this.status = manager.start();
        } catch (RuntimeException e) {
            this.status = new PluginRuntimeStatus(pluginsRoot, PluginDirectoryState.ABSENT,
                    List.of(), List.of(), List.of(new PluginLoadFailure(pluginsRoot.toString(),
                    "plugin runtime start failed: " + describe(e))));
            diagnostics.add("plugin runtime start failed: " + describe(e));
            log.warn("Plugin runtime start failed: {}", describe(e), e);
        }
        runStartupDiscovery();
        this.started = true;
        return this;
    }

    /**
     * 启动期 discovery：从同一 manager 做一次清点（{@code inspectPlugins}）并据此投影 discovery
     *（{@code toDiscoveryResult}），二者共用同一次 provider 调用、不重复清点。失败收敛为空 inventory / discovery + 诊断，
     * 不阻断 start。在 {@code start()} 内调用一次；幂等 start 下不会再次执行。
     */
    private void runStartupDiscovery() {
        try {
            PluginInventory inventory = manager.inspectPlugins();
            this.startupInventory = inventory;
            this.startupDiscovery = manager.toDiscoveryResult(inventory);
        } catch (RuntimeException e) {
            this.startupInventory = PluginInventory.empty();
            this.startupDiscovery = PluginDiscoveryResult.empty();
            diagnostics.add("startup plugin discovery failed: " + describe(e));
            log.warn("Startup plugin discovery failed: {}", describe(e), e);
        }
    }

    public PluginRuntimeManager manager() {
        return manager;
    }

    public ExternalPluginInstaller installer() {
        return installer;
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
        try {
            manager.shutdown();
        } catch (RuntimeException e) {
            diagnostics.add("plugin runtime shutdown failed: " + describe(e));
            log.warn("Plugin runtime shutdown failed: {}", describe(e), e);
        }
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getName() : error.getMessage();
    }

    /** 会话所有权。 */
    public enum Ownership {
        /** GUI 进程拥有：Spring context 关闭不释放，进程退出时关闭；后端 restart 复用同一运行时。 */
        PROCESS,
        /** headless / Spring context 拥有：context 关闭时释放运行时。 */
        CONTEXT
    }
}
