package top.sywyar.pixivdownload.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import top.sywyar.pixivdownload.PixivDownloadApplication;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Manages the embedded Spring Boot backend for GUI actions that need
 * exclusive access to SQLite.
 *
 * <p>并发与生命周期模型：每个 start / stop 都是一次带单调代际令牌的 lifecycle operation。
 * 启动线程在 LOCK 内捕获<b>不可变</b>的 starter / 参数 / 失败处理器 / operation 代际，执行期间不再读取可能被
 * {@link #configure} 替换的静态字段。starter 返回 context 后，发布 RUNNING 前重新进入 LOCK 复核：operation 代际仍有效、
 * 未发起进程退出（{@code shutdownInitiated}）、状态仍为 STARTING——任一不满足即丢弃「迟到的 context」（由启动线程自行关闭）
 * 并落 STOPPED，绝不把已失效的 context 发布成 RUNNING。
 *
 * <p>{@link #closeBackendContext(long)} 是进程退出的同步关闭点，按当前状态路由，且<b>不</b>在退出过程中并发拆第二个 context：
 * <ul>
 *   <li>STARTING：使在途 start 失效并有限等待；迟到 context 由启动线程自行关闭并落 STOPPED；
 *       超时则返回 {@link CloseResult#TIMED_OUT} 且<b>不</b>伪装 STOPPED；</li>
 *   <li>RUNNING / FAILED(残留 context)：取走唯一 context、置 STOPPING、有限超时同步关闭；</li>
 *   <li>STOPPING：有限等待既有 stop operation 落定，<b>不</b>启动第二个 close；</li>
 *   <li>STOPPED：直接 {@link CloseResult#ALREADY_STOPPED}。</li>
 * </ul>
 * 调用方据 {@link CloseResult#isContextConfirmedClosed()} 决定是否继续清退下游（如 PF4J classloader）——未确认关闭时
 * 不得并发卸载依赖 Spring context 的资源。
 */
public final class BackendLifecycleManager {

    public enum State {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        FAILED
    }

    public record Snapshot(State state, Throwable error) {}

    @FunctionalInterface
    public interface Listener {
        void onStateChanged(Snapshot snapshot);
    }

    /**
     * 后端启动回调：把「如何用给定参数启动 Spring context」抽象出来，使进程级 bootstrap 会话经显式、可清理的回调
     * 传递给后端生命周期管理器，而非以 static {@code PluginBootstrapSession} 字段持有（避免跨测试残留）。
     * GUI 在 {@code configure} 时注入一个捕获了 PROCESS 会话的回调；headless / 回退路径注入直连 {@code start(args)} 的回调。
     */
    @FunctionalInterface
    public interface BackendStarter {
        ConfigurableApplicationContext start(String[] args);
    }

    /**
     * 进程退出关闭后端 Spring context 的结果，供协调器决定是否继续下游清退（如 PF4J classloader 卸载）。
     * <ul>
     *   <li>{@link #CLOSED}：context 原本在场、现已确认关闭；</li>
     *   <li>{@link #ALREADY_STOPPED}：无 context 需关闭（已 STOPPED，或在途 start / stop 自行落定）；</li>
     *   <li>{@link #TIMED_OUT}：关闭仍在进行（worker 仍在拆 context / 在途 operation 未落定），<b>未</b>确认关闭；</li>
     *   <li>{@link #FAILED}：关闭抛错、context 状态未知（保守按未确认关闭处理）。</li>
     * </ul>
     */
    public enum CloseResult {
        CLOSED,
        ALREADY_STOPPED,
        TIMED_OUT,
        FAILED;

        /** context 是否已确认关闭（可安全继续依赖它的下游清退）。TIMED_OUT / FAILED 视为未确认。 */
        public boolean isContextConfirmedClosed() {
            return this == CLOSED || this == ALREADY_STOPPED;
        }
    }

    /**
     * 一次有限等待的关闭观测：{@link #result()} 是调用返回时的状态；若其为 {@link CloseResult#TIMED_OUT}，
     * {@link #completion()} 会在同一 lifecycle operation 最终确认关闭或失败时完成。调用方可据此把依赖清退
     * 串到 context 的真实完成点之后，不能在 timeout 后并发卸载 PF4J classloader。
     */
    public static final class CloseAttempt {
        private final CloseResult result;
        private final CompletionStage<CloseResult> completion;

        private CloseAttempt(CloseResult result, CompletionStage<CloseResult> completion) {
            this.result = Objects.requireNonNull(result, "result");
            this.completion = Objects.requireNonNull(completion, "completion");
        }

        public static CloseAttempt completed(CloseResult result) {
            return new CloseAttempt(result, CompletableFuture.completedFuture(result));
        }

        public static CloseAttempt timedOut(CompletionStage<CloseResult> completion) {
            return new CloseAttempt(CloseResult.TIMED_OUT, completion);
        }

        public CloseResult result() {
            return result;
        }

        public CompletionStage<CloseResult> completion() {
            return completion;
        }

        public boolean isContextConfirmedClosed() {
            return result.isContextConfirmedClosed();
        }
    }

    private static final class StartOperation {
        private final long generation;
        private final CompletableFuture<CloseResult> completion = new CompletableFuture<>();
        private boolean closeRequested;

        private StartOperation(long generation) {
            this.generation = generation;
        }
    }

    private static final class StopOperation {
        private final long generation;
        private final ConfigurableApplicationContext ownedContext;
        private final CompletableFuture<CloseResult> completion = new CompletableFuture<>();

        private StopOperation(long generation, ConfigurableApplicationContext ownedContext) {
            this.generation = generation;
            this.ownedContext = ownedContext;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(BackendLifecycleManager.class);
    private static final Object LOCK = new Object();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    /** 默认启动回调：直连 headless {@link PixivDownloadApplication#start(String[])}，不捕获任何 bootstrap 会话。 */
    private static final BackendStarter DEFAULT_STARTER = PixivDownloadApplication::start;

    private static volatile ConfigurableApplicationContext context;
    private static volatile State state = State.STOPPED;
    private static volatile Throwable lastError;
    private static volatile String[] applicationArgs = new String[0];
    private static volatile Consumer<Throwable> startupFailureHandler = error -> {};
    private static volatile BackendStarter starter = DEFAULT_STARTER;
    /** 当前配置代际：每次 configure 自增；Registration 据此判断自己是否仍是当前配置。 */
    private static volatile long starterGeneration = 0L;
    /**
     * 进程退出一次性闩锁：一旦 {@link #forbidLifecycle()} 置位即<b>不可恢复</b>——之后 startAsync / restartAsync /
     * {@link #configure} 一律拒绝，杜绝退出过程中又拉起新后端或重新开放生命周期。该闩锁属于进程生命周期状态、
     * <b>不</b>属于 Registration 配置状态，故 {@link Registration#close()} 不得清它；只有进程初值与 {@link #resetForTests()}
     * 能复位。
     */
    private static volatile boolean shutdownInitiated = false;

    /** start / stop operation 共用的单调代际源。 */
    private static volatile long lifecycleOperationCounter = 0L;
    /** 当前 worker operation；完成后清空，既是唯一 context owner，也是有限等待句柄。 */
    private static volatile StartOperation activeStartOperation;
    private static volatile StopOperation activeStopOperation;

    private BackendLifecycleManager() {}

    /**
     * 配置后端启动参数、失败处理器与启动回调，返回一个可关闭的 {@link Registration}（代际句柄）。
     *
     * <p>进程退出闩锁一旦置位即不可恢复——此时再 configure 显式拒绝（抛 {@link IllegalStateException}），不会静默重新开放
     * 生命周期。{@code starter == null} 时显式回落到默认回调（不静默保留上一次的、可能捕获旧会话的回调）。
     *
     * <p>{@link Registration#close()} 只清理配置态字段（starter / 参数 / 失败处理器 / 代际引用），<b>不</b>触碰
     * {@code shutdownInitiated}——它是进程级闩锁而非配置态。若已被更新的 configure 取代，旧句柄关闭是 no-op。
     */
    public static Registration configure(String[] args, Consumer<Throwable> failureHandler, BackendStarter starter) {
        synchronized (LOCK) {
            if (shutdownInitiated) {
                throw new IllegalStateException(
                        "backend lifecycle has been shut down; cannot reconfigure after process exit forbid");
            }
            applicationArgs = args == null ? new String[0] : Arrays.copyOf(args, args.length);
            startupFailureHandler = failureHandler != null ? failureHandler : error -> {};
            BackendLifecycleManager.starter = starter != null ? starter : DEFAULT_STARTER;
            long generation = ++starterGeneration;
            return new Registration(generation);
        }
    }

    /**
     * 进程退出协调器第一步：禁止后续 backend start / restart（置位后 startAsync / restartAsync / configure 立即拒绝），
     * 并使在途的 start operation 失效（其发布前会复核本标志、丢弃迟到 context）。只置标志、不关闭 context——context
     * 关闭由 {@link #closeBackendContext(long)} 同步执行，确保 PF4J classloader 卸载发生在 Spring context 拆卸之后。
     * 该闩锁<b>不可恢复</b>：进程退出是单向的，重开只会引入退出过程中又拉起后端的竞态。
     */
    public static void forbidLifecycle() {
        synchronized (LOCK) {
            shutdownInitiated = true;
            if (activeStartOperation != null) {
                activeStartOperation.closeRequested = true;
            }
        }
    }

    /**
     * 进程退出同步关闭后端 Spring context（有限超时），返回 {@link CloseAttempt}；其即时结果与最终 completion 供调用方决定何时继续下游清退。
     * 幂等——backend 已停 / 无 context 时返回 {@link CloseResult#ALREADY_STOPPED}。这是「context 清退先于 session 关闭」
     * 顺序的同步保证点：未确认关闭（{@link CloseResult#TIMED_OUT} / {@link CloseResult#FAILED}）时调用方不得并发卸载
     * 依赖 Spring context 的资源。
     *
     * @param timeoutMillis 关闭 / 等待在途 operation 的有限超时，必须为正（<=0 拒绝，禁止退化为无限等待）
     */
    public static CloseAttempt closeBackendContext(long timeoutMillis) {
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis must be positive: " + timeoutMillis);
        }

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        CompletableFuture<CloseResult> completion;
        StopOperation stopToStart = null;

        synchronized (LOCK) {
            switch (state) {
                case STOPPED:
                    return CloseAttempt.completed(CloseResult.ALREADY_STOPPED);
                case STARTING:
                    if (activeStartOperation == null) {
                        updateState(State.FAILED,
                                new IllegalStateException("STARTING without an active start operation"));
                        return CloseAttempt.completed(CloseResult.FAILED);
                    }
                    activeStartOperation.closeRequested = true;
                    completion = activeStartOperation.completion;
                    break;
                case STOPPING:
                    if (activeStopOperation != null) {
                        completion = activeStopOperation.completion;
                    } else if (activeStartOperation != null) {
                        // 失效的 start 已拿到迟到 context，当前由 start worker 负责唯一关闭。
                        completion = activeStartOperation.completion;
                    } else {
                        updateState(State.FAILED,
                                new IllegalStateException("STOPPING without an active lifecycle operation"));
                        return CloseAttempt.completed(CloseResult.FAILED);
                    }
                    break;
                case RUNNING:
                case FAILED:
                    if (context == null) {
                        updateState(State.STOPPED, null);
                        return CloseAttempt.completed(CloseResult.ALREADY_STOPPED);
                    }
                    stopToStart = beginStopLocked(context);
                    completion = stopToStart.completion;
                    break;
                default:
                    return CloseAttempt.completed(CloseResult.FAILED);
            }
        }

        if (stopToStart != null) {
            startStopWorker(stopToStart, null);
        }
        return awaitCloseAttempt(completion, deadlineNanos);
    }

    private static StopOperation beginStopLocked(ConfigurableApplicationContext ownedContext) {
        StopOperation operation = new StopOperation(++lifecycleOperationCounter, ownedContext);
        context = null;
        activeStopOperation = operation;
        updateState(State.STOPPING, null);
        return operation;
    }

    private static CloseAttempt awaitCloseAttempt(
            CompletableFuture<CloseResult> completion, long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return CloseAttempt.timedOut(completion);
        }
        try {
            return CloseAttempt.completed(completion.get(remainingNanos, TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            return CloseAttempt.timedOut(completion);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CloseAttempt.timedOut(completion);
        } catch (ExecutionException e) {
            log.warn("Backend context close operation failed unexpectedly: {}", safeMessage(e), e);
            return CloseAttempt.completed(CloseResult.FAILED);
        }
    }

    /**
     * 关闭唯一拥有的 context，并依据 {@code isActive()} 区分「虽有异常但已确认关闭」和「未确认关闭」。
     * 只有前者允许继续清退 PF4J；后者保留 context 引用供后续重试。
     */
    private static CloseResult closeOwnedContext(ConfigurableApplicationContext ctx) {
        Throwable failure = null;
        try {
            int exitCode = SpringApplication.exit(ctx);
            if (exitCode != 0) {
                log.warn("Backend context exit reported non-zero code: {}", exitCode);
            }
        } catch (Throwable t) {
            failure = t;
        }

        boolean active;
        try {
            active = ctx.isActive();
        } catch (Throwable t) {
            if (failure == null) {
                failure = t;
            } else {
                failure.addSuppressed(t);
            }
            active = true;
        }

        if (failure != null) {
            log.warn("Error closing backend context: {}", safeMessage(failure), failure);
        }
        return active ? CloseResult.FAILED : CloseResult.CLOSED;
    }

    private static void startStopWorker(StopOperation operation, Runnable afterStop) {
        Thread stopper = new Thread(() -> {
            CloseResult result = closeOwnedContext(operation.ownedContext);
            synchronized (LOCK) {
                if (activeStopOperation == operation
                        && activeStopOperation.generation == operation.generation) {
                    if (result == CloseResult.CLOSED) {
                        updateState(State.STOPPED, null);
                    } else {
                        context = operation.ownedContext;
                        updateState(State.FAILED,
                                new IllegalStateException("backend context close was not confirmed"));
                    }
                    activeStopOperation = null;
                }
            }
            operation.completion.complete(result);
            if (result == CloseResult.CLOSED && afterStop != null) {
                runOnEdt(afterStop);
            }
        }, "spring-stop");
        stopper.setDaemon(false);
        stopper.start();
    }

    private static String safeMessage(Throwable t) {
        Throwable cause = t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /** 测试观测：当前 starter 是否为默认回调（{@link Registration#close()} 后应恢复为 true）。 */
    static boolean usesDefaultStarter() {
        return starter == DEFAULT_STARTER;
    }

    /** 测试观测：进程退出 forbid 是否已发起。 */
    static boolean isShutdownInitiated() {
        return shutdownInitiated;
    }

    /** 测试观测：当前配置代际。 */
    static long currentStarterGeneration() {
        return starterGeneration;
    }

    /**
     * 测试专用全量复位：把全部静态状态恢复到初值。生产代码不得调用。
     *
     * <p>不掩盖生命周期泄漏：先禁止新启动并有限关闭 / 等待在途 operation；只有 context 已确认关闭且无活动 worker
     * 才清空静态状态。超时、未确认关闭或残留 operation 均抛 {@link AssertionError}，让污染测试明确失败。
     */
    static void resetForTests() {
        synchronized (LOCK) {
            shutdownInitiated = true;
            if (activeStartOperation != null) {
                activeStartOperation.closeRequested = true;
            }
        }

        CloseAttempt attempt = closeBackendContext(5_000L);
        CloseResult terminal = attempt.result();
        if (terminal == CloseResult.TIMED_OUT) {
            try {
                terminal = attempt.completion().toCompletableFuture().get(5_000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while cleaning backend lifecycle test state", e);
            } catch (ExecutionException | TimeoutException e) {
                throw new AssertionError("backend lifecycle worker did not terminate during test cleanup", e);
            }
        }
        if (!terminal.isContextConfirmedClosed()) {
            throw new AssertionError("backend context was not confirmed closed during test cleanup: " + terminal);
        }

        synchronized (LOCK) {
            if (activeStartOperation != null || activeStopOperation != null || context != null
                    || state == State.STARTING || state == State.RUNNING || state == State.STOPPING) {
                throw new AssertionError("active backend lifecycle state remained after bounded test cleanup: "
                        + state);
            }
            starter = DEFAULT_STARTER;
            applicationArgs = new String[0];
            startupFailureHandler = error -> {};
            shutdownInitiated = false;
            starterGeneration = 0L;
            lifecycleOperationCounter = 0L;
            activeStartOperation = null;
            activeStopOperation = null;
            context = null;
            state = State.STOPPED;
            lastError = null;
            LISTENERS.clear();
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(state, lastError);
    }

    public static State state() {
        return state;
    }

    public static boolean isRunning() {
        return state == State.RUNNING;
    }

    public static void addListener(Listener listener) {
        Listener safeListener = Objects.requireNonNull(listener, "listener");
        LISTENERS.add(safeListener);
        notifyListener(safeListener, snapshot());
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static boolean startAsync() {
        return startAsync(null);
    }

    /**
     * 启动后端。在 LOCK 内捕获 starter / 参数副本 / 失败处理器 / operation 代际，worker 不再读取可被 configure
     * 替换的配置。进程退出闩锁置位时立即拒绝。
     */
    public static boolean startAsync(Runnable afterStart) {
        final BackendStarter starterFn;
        final String[] args;
        final Consumer<Throwable> failureHandler;
        final StartOperation operation;
        synchronized (LOCK) {
            if (shutdownInitiated || state == State.STARTING || state == State.RUNNING
                    || state == State.STOPPING || (state == State.FAILED && context != null)) {
                return false;
            }
            starterFn = starter;
            args = Arrays.copyOf(applicationArgs, applicationArgs.length);
            failureHandler = startupFailureHandler;
            operation = new StartOperation(++lifecycleOperationCounter);
            activeStartOperation = operation;
            updateState(State.STARTING, null);
        }

        Thread starterThread = new Thread(
                () -> runStart(starterFn, args, failureHandler, operation, afterStart),
                "spring-main");
        starterThread.setDaemon(false);
        starterThread.start();
        return true;
    }

    private static void runStart(BackendStarter starterFn, String[] args,
                                 Consumer<Throwable> failureHandler, StartOperation operation,
                                 Runnable afterStart) {
        ConfigurableApplicationContext created = null;
        Throwable failure = null;
        try {
            created = starterFn.start(args);
            if (created == null) {
                failure = new IllegalStateException("backend starter returned null context");
            }
        } catch (Throwable t) {
            failure = t;
        }

        ConfigurableApplicationContext lateClose = null;
        boolean invokeAfterStart = false;
        boolean invokeFailure = false;
        CloseResult operationResult;

        synchronized (LOCK) {
            boolean owner = activeStartOperation == operation
                    && activeStartOperation.generation == operation.generation;
            boolean closeRequested = operation.closeRequested || shutdownInitiated;
            if (failure != null) {
                if (owner) {
                    if (closeRequested) {
                        updateState(State.STOPPED, null);
                        operationResult = CloseResult.ALREADY_STOPPED;
                    } else {
                        updateState(State.FAILED, failure);
                        operationResult = CloseResult.FAILED;
                        invokeFailure = !(failure instanceof Error);
                    }
                    activeStartOperation = null;
                } else {
                    operationResult = CloseResult.FAILED;
                }
            } else if (owner && !closeRequested && state == State.STARTING) {
                context = created;
                updateState(State.RUNNING, null);
                activeStartOperation = null;
                operationResult = CloseResult.ALREADY_STOPPED;
                invokeAfterStart = true;
            } else {
                lateClose = created;
                operationResult = CloseResult.FAILED;
                if (owner) {
                    updateState(State.STOPPING, null);
                }
            }
        }

        if (lateClose != null) {
            CloseResult closeResult = closeOwnedContext(lateClose);
            synchronized (LOCK) {
                if (activeStartOperation == operation
                        && activeStartOperation.generation == operation.generation) {
                    if (closeResult == CloseResult.CLOSED) {
                        updateState(State.STOPPED, null);
                    } else {
                        context = lateClose;
                        updateState(State.FAILED,
                                new IllegalStateException("late backend context close was not confirmed"));
                    }
                    activeStartOperation = null;
                }
            }
            operationResult = closeResult;
        }

        operation.completion.complete(operationResult);
        if (invokeFailure) {
            try {
                failureHandler.accept(failure);
            } catch (RuntimeException handlerFailure) {
                log.warn("Backend startup failure handler failed: {}", safeMessage(handlerFailure),
                        handlerFailure);
            }
        }
        if (invokeAfterStart && afterStart != null) {
            runOnEdt(afterStart);
        }
        if (failure instanceof Error fatal) {
            throw fatal;
        }
    }

    public static boolean stopAsync(Runnable afterStop) {
        final StopOperation operation;
        synchronized (LOCK) {
            if (state == State.STOPPING || state == State.STARTING) {
                return false;
            }
            if (state == State.STOPPED || (state == State.FAILED && context == null)) {
                updateState(State.STOPPED, null);
                if (afterStop != null) {
                    runOnEdt(afterStop);
                }
                return true;
            }
            if (context == null) {
                updateState(State.FAILED,
                        new IllegalStateException("backend state has no owned context: " + state));
                return false;
            }
            operation = beginStopLocked(context);
        }

        startStopWorker(operation, afterStop);
        return true;
    }
    public static boolean restartAsync() {
        return restartAsync(null);
    }

    public static boolean restartAsync(Runnable afterRestart) {
        if (shutdownInitiated) {
            return false;
        }
        Snapshot current = snapshot();
        if (current.state == State.STARTING || current.state == State.STOPPING) {
            return false;
        }
        return stopAsync(() -> startAsync(afterRestart));
    }

    private static void updateState(State newState, Throwable error) {
        state = newState;
        lastError = error;
        Snapshot snapshot = new Snapshot(newState, error);
        for (Listener listener : LISTENERS) {
            notifyListener(listener, snapshot);
        }
    }

    private static void notifyListener(Listener listener, Snapshot snapshot) {
        runOnEdt(() -> listener.onStateChanged(snapshot));
    }

    private static void runOnEdt(Runnable action) {
        if (action == null) {
            return;
        }
        if (GraphicsEnvironment.isHeadless() || SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    /**
     * {@link #configure configure(...)} 返回的可关闭配置句柄（代际令牌）。GUI 进程退出时经其 {@link #close()} 把静态
     * starter 恢复为默认（释放对 PROCESS bootstrap 会话的静态引用），同时复位参数 / 失败处理器。
     *
     * <p>代际安全：{@link #close()} 仅在自己仍是当前配置代际（{@code starterGeneration == generation}）时复位；若一次新的
     * configure 已取代自己，则旧句柄关闭是 no-op，不会误清新的配置。{@link #isActive()} 暴露「是否仍是当前配置」供观测。
     * 多次关闭安全（幂等）。
     *
     * <p><b>不</b>触碰进程退出闩锁 {@code shutdownInitiated}——它是进程级状态而非配置态，{@link #close()} 撤销它会让
     * 「forbid 后 Registration.close 又重新开放生命周期」的竞态回潮。
     */
    public static final class Registration implements AutoCloseable {
        private final long generation;
        private volatile boolean closed;

        private Registration(long generation) {
            this.generation = generation;
        }

        /** 本句柄是否仍未关闭且仍是当前配置代际。 */
        public boolean isActive() {
            return !closed && starterGeneration == generation;
        }

        @Override
        public void close() {
            synchronized (LOCK) {
                if (closed) {
                    return;
                }
                closed = true;
                if (starterGeneration == generation) {
                    starter = DEFAULT_STARTER;
                    applicationArgs = new String[0];
                    startupFailureHandler = error -> {};
                    starterGeneration++;
                }
            }
        }
    }
}
