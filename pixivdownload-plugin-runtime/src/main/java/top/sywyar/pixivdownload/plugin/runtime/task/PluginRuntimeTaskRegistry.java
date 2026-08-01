package top.sywyar.pixivdownload.plugin.runtime.task;

import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTask;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskDrain;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRegistrar;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRejectedException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * 插件后台任务的宿主注册中心。
 *
 * <p>插件子 context 只获得由 {@link #registrarForPlugin(String)} 创建的 owner-scoped
 * {@link PluginRuntimeTaskRegistrar}。登记后，父加载器创建的包装器而非插件 lambda 被提交到共享执行器；
 * quiesce 在线性化点关闭 admission 并清除尚未运行的插件 delegate，运行中的 delegate 则由包装器
 * {@code finally} 精确归还。注册中心不保存插件 Bean、classloader 或子 context。
 *
 * <p>{@link #prepareQuiesce(String)} 只在宿主内存中关闭 admission、清 delegate 并返回精确 drain，
 * 不调用 {@link Future#cancel(boolean)} 或其它外部 callback。调用方保存 drain 后再调用
 * {@link #cancelQuiescedTasks(String, PluginRuntimeTaskDrain)}；取消抛错或返回拒绝时，精确包装器与句柄保留供
 * 下一次重试，drain 在成功取消或真实运行退出前不会归零。成功 {@link #resume(String)} 会建立新代际，
 * 旧 drain 永久保持旧代际视图。
 */
public class PluginRuntimeTaskRegistry {

    private final Map<String, OwnerSlot> byPlugin = new ConcurrentHashMap<>();

    /**
     * 为可信插件 owner 创建专属登记入口。入口只保存本注册中心和固定字符串 owner。
     */
    public PluginRuntimeTaskRegistrar registrarForPlugin(String pluginId) {
        return new ScopedRegistrar(this, requirePluginId(pluginId));
    }

    /**
     * 原子关闭指定 owner 当前代际的新任务 admission，清除未运行 delegate，并返回可先保存的精确 drain。
     * 同一代际重复调用返回同一个 drain；本方法不调用任何 {@link Future}。
     */
    public PluginRuntimeTaskDrain prepareQuiesce(String pluginId) {
        OwnerSlot slot = ownerSlot(pluginId);
        synchronized (slot) {
            GenerationState generation = slot.current;
            synchronized (generation) {
                generation.accepting = false;
                if (generation.drain == null) {
                    generation.drain = new Drain(generation);
                }
                List<RuntimeTask> released = new ArrayList<>();
                for (RuntimeTask task : generation.active) {
                    if (task.prepareQuiesce()) {
                        released.add(task);
                    }
                }
                if (!released.isEmpty()) {
                    generation.active.removeAll(released);
                }
                if (generation.active.isEmpty()) {
                    generation.notifyAll();
                }
                return generation.drain;
            }
        }
    }

    /**
     * 向精确 quiesced 代际的已绑定 {@link Future} 发送取消。所有任务都会尝试；普通失败完整轮询后重抛，
     * 致命失败保持原对象身份并优先重抛。失败项留在 drain 中供下一次调用重试。
     */
    public void cancelQuiescedTasks(String pluginId, PluginRuntimeTaskDrain drain) {
        OwnerSlot slot = ownerSlot(pluginId);
        GenerationState generation = requireCurrentDrain(slot, drain);
        List<RuntimeTask> snapshot;
        synchronized (generation) {
            snapshot = new ArrayList<>(generation.active);
        }
        Throwable failure = null;
        for (RuntimeTask task : snapshot) {
            try {
                task.cancelForQuiesce();
            } catch (Throwable cancellationFailure) {
                failure = mergeFailure(failure, cancellationFailure);
            }
        }
        rethrow(failure);
    }

    /**
     * 当前代际完全归零后重新开放 owner admission，并建立新的正代际。尚有活动或取消失败残留时 fail-closed。
     */
    public void resume(String pluginId) {
        OwnerSlot slot = ownerSlot(pluginId);
        synchronized (slot) {
            GenerationState current = slot.current;
            synchronized (current) {
                if (!current.active.isEmpty()) {
                    throw new IllegalStateException("cannot resume plugin runtime tasks with pending work: "
                            + slot.ownerPluginId + " (active=" + current.active.size() + ")");
                }
                if (current.accepting) {
                    return;
                }
                if (current.generation == Long.MAX_VALUE) {
                    throw new IllegalStateException("plugin runtime task generation exhausted: "
                            + slot.ownerPluginId);
                }
                slot.current = new GenerationState(slot.ownerPluginId, current.generation + 1L);
            }
        }
    }

    /** 当前精确 owner 的活动包装器数量。 */
    public int activeTaskCount(String pluginId) {
        if (isBlank(pluginId)) {
            return 0;
        }
        OwnerSlot slot = byPlugin.get(pluginId);
        if (slot == null) {
            return 0;
        }
        GenerationState current;
        synchronized (slot) {
            current = slot.current;
        }
        return current.activeCount();
    }

    /** 当前精确 owner 是否允许登记新后台任务。 */
    public boolean acceptsNewTasks(String pluginId) {
        if (isBlank(pluginId)) {
            return true;
        }
        OwnerSlot slot = byPlugin.get(pluginId);
        if (slot == null) {
            return true;
        }
        GenerationState current;
        synchronized (slot) {
            current = slot.current;
        }
        return current.isAccepting();
    }

    private PluginRuntimeTask register(String pluginId, Runnable delegate, TaskKind kind) {
        Objects.requireNonNull(delegate, "delegate");
        OwnerSlot slot = ownerSlot(pluginId);
        synchronized (slot) {
            GenerationState generation = slot.current;
            synchronized (generation) {
                if (!generation.accepting) {
                    throw new PluginRuntimeTaskRejectedException();
                }
                RuntimeTask task = new RuntimeTask(generation, kind, delegate);
                generation.active.add(task);
                return task;
            }
        }
    }

    private GenerationState requireCurrentDrain(OwnerSlot slot, PluginRuntimeTaskDrain candidate) {
        Objects.requireNonNull(candidate, "drain");
        synchronized (slot) {
            GenerationState current = slot.current;
            synchronized (current) {
                if (!(candidate instanceof Drain drain)
                        || drain.generation != current
                        || current.drain != drain
                        || current.accepting
                        || !Objects.equals(slot.ownerPluginId, drain.ownerPluginId())) {
                    throw new IllegalStateException("plugin runtime task drain is not current for owner: "
                            + slot.ownerPluginId);
                }
                return current;
            }
        }
    }

    private OwnerSlot ownerSlot(String pluginId) {
        String owner = requirePluginId(pluginId);
        return byPlugin.computeIfAbsent(owner, OwnerSlot::new);
    }

    private enum TaskKind {
        ONE_SHOT,
        PERIODIC
    }

    private static final class ScopedRegistrar implements PluginRuntimeTaskRegistrar {
        private final PluginRuntimeTaskRegistry registry;
        private final String pluginId;

        private ScopedRegistrar(PluginRuntimeTaskRegistry registry, String pluginId) {
            this.registry = registry;
            this.pluginId = pluginId;
        }

        @Override
        public PluginRuntimeTask registerOneShot(Runnable delegate) {
            return registry.register(pluginId, delegate, TaskKind.ONE_SHOT);
        }

        @Override
        public PluginRuntimeTask registerPeriodic(Runnable delegate) {
            return registry.register(pluginId, delegate, TaskKind.PERIODIC);
        }

        @Override
        public boolean acceptsNewTasks() {
            return registry.acceptsNewTasks(pluginId);
        }
    }

    private static final class OwnerSlot {
        private final String ownerPluginId;
        private GenerationState current;

        private OwnerSlot(String ownerPluginId) {
            this.ownerPluginId = ownerPluginId;
            this.current = new GenerationState(ownerPluginId, 1L);
        }
    }

    /**
     * 单个 admission 代际的宿主状态。旧 drain 只保留本对象；resume 会把 owner slot 指向一个全新对象，
     * 因而新任务不会污染旧 drain 的归零视图。
     */
    private static final class GenerationState {
        private final String ownerPluginId;
        private final long generation;
        private final Set<RuntimeTask> active = new LinkedHashSet<>();
        private boolean accepting = true;
        private Drain drain;

        private GenerationState(String ownerPluginId, long generation) {
            this.ownerPluginId = ownerPluginId;
            this.generation = generation;
        }

        private synchronized void release(RuntimeTask task) {
            if (active.remove(task) && active.isEmpty()) {
                notifyAll();
            }
        }

        private synchronized int activeCount() {
            return active.size();
        }

        private synchronized boolean isAccepting() {
            return accepting;
        }

        private synchronized boolean isDrained() {
            return active.isEmpty();
        }

        private synchronized boolean awaitDrained(long deadlineNanos) {
            while (!active.isEmpty()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                long millis = remaining / 1_000_000L;
                int nanos = (int) (remaining % 1_000_000L);
                try {
                    wait(millis, nanos);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }

        private synchronized boolean awaitDrained() {
            while (!active.isEmpty()) {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    private static final class Drain implements PluginRuntimeTaskDrain {
        private final GenerationState generation;

        private Drain(GenerationState generation) {
            this.generation = generation;
        }

        @Override
        public String ownerPluginId() {
            return generation.ownerPluginId;
        }

        @Override
        public long generation() {
            return generation.generation;
        }

        @Override
        public int activeCount() {
            return generation.activeCount();
        }

        @Override
        public boolean isDrained() {
            return generation.isDrained();
        }

        @Override
        public boolean awaitDrained(long deadlineNanos) {
            return generation.awaitDrained(deadlineNanos);
        }

        @Override
        public boolean awaitDrained() {
            return generation.awaitDrained();
        }
    }

    /**
     * 父加载器任务空壳。终态后只保留宿主 generation 状态与枚举，不保留插件 delegate、Future 或失败对象。
     */
    private static final class RuntimeTask implements PluginRuntimeTask {
        private final GenerationState generation;
        private final TaskKind kind;

        private Runnable delegate;
        private Future<?> cancellation;
        private boolean cancellationRequested;
        private boolean cancellationComplete;
        private boolean cancellationAttemptInProgress;
        private boolean terminal;
        private int runningCount;

        private RuntimeTask(GenerationState generation, TaskKind kind, Runnable delegate) {
            this.generation = generation;
            this.kind = kind;
            this.delegate = delegate;
        }

        @Override
        public void bindCancellation(Future<?> future) {
            Objects.requireNonNull(future, "cancellation");
            boolean cancelLateBinding = false;
            synchronized (this) {
                if (cancellation != null) {
                    if (cancellation != future) {
                        throw new IllegalStateException("plugin runtime task cancellation is already bound");
                    }
                    return;
                }
                if (terminal) {
                    cancelLateBinding = cancellationRequested;
                } else {
                    cancellation = future;
                    cancelLateBinding = cancellationRequested;
                }
            }
            if (cancelLateBinding) {
                if (isTerminal()) {
                    cancelFuture(future);
                } else {
                    requestCancellation();
                }
            }
        }

        @Override
        public void cancel() {
            requestCancellation();
        }

        @Override
        public void discardUnsubmitted() {
            boolean release;
            synchronized (this) {
                if (terminal) {
                    return;
                }
                if (cancellation != null || cancellationAttemptInProgress || runningCount != 0) {
                    throw new IllegalStateException(
                            "cannot discard a submitted or running plugin runtime task");
                }
                cancellationRequested = true;
                cancellationComplete = true;
                terminal = true;
                clearHostReferences();
                release = true;
            }
            if (release) {
                generation.release(this);
            }
        }

        @Override
        public void run() {
            Runnable taskDelegate;
            boolean oneShot;
            boolean releaseWithoutRun = false;
            synchronized (this) {
                if (terminal) {
                    return;
                }
                oneShot = kind == TaskKind.ONE_SHOT;
                if (cancellationRequested || delegate == null) {
                    if (oneShot) {
                        terminal = true;
                        clearHostReferences();
                        releaseWithoutRun = true;
                    }
                    taskDelegate = null;
                } else {
                    runningCount++;
                    taskDelegate = delegate;
                    if (oneShot) {
                        delegate = null;
                    }
                }
            }
            if (releaseWithoutRun) {
                generation.release(this);
                return;
            }
            if (taskDelegate == null) {
                return;
            }

            Throwable taskFailure = null;
            try {
                taskDelegate.run();
            } catch (Throwable failure) {
                taskFailure = failure;
                rethrow(failure);
            } finally {
                boolean release = false;
                synchronized (this) {
                    runningCount--;
                    if (oneShot
                            || (taskFailure != null && !cancellationRequested)
                            || (cancellationRequested && cancellationComplete && runningCount == 0)) {
                        terminal = true;
                        clearHostReferences();
                        release = true;
                    }
                }
                if (release) {
                    generation.release(this);
                }
            }
        }

        /**
         * prepare 持有 generation 锁时调用；只改宿主内存，不触达 Future。返回是否可立即从活动集合摘除。
         */
        private synchronized boolean prepareQuiesce() {
            if (terminal) {
                return true;
            }
            cancellationRequested = true;
            delegate = null;
            return false;
        }

        private void cancelForQuiesce() {
            requestCancellation();
        }

        private void requestCancellation() {
            Future<?> future = null;
            boolean release = false;
            synchronized (this) {
                if (terminal) {
                    return;
                }
                cancellationRequested = true;
                delegate = null;
                if (cancellationComplete) {
                    if (runningCount == 0) {
                        terminal = true;
                        clearHostReferences();
                        release = true;
                    }
                } else if (cancellation != null && !cancellationAttemptInProgress) {
                    cancellationAttemptInProgress = true;
                    future = cancellation;
                }
            }
            if (release) {
                generation.release(this);
            }
            if (future == null) {
                return;
            }

            Throwable failure = null;
            try {
                if (!future.cancel(false) && !future.isDone()) {
                    failure = new IllegalStateException("plugin runtime task cancellation was refused");
                }
            } catch (Throwable cancellationFailure) {
                failure = cancellationFailure;
            }

            release = false;
            synchronized (this) {
                cancellationAttemptInProgress = false;
                if (failure == null && cancellation == future) {
                    cancellation = null;
                    cancellationComplete = true;
                    if (runningCount == 0) {
                        terminal = true;
                        clearHostReferences();
                        release = true;
                    }
                }
                notifyAll();
            }
            if (release) {
                generation.release(this);
            }
            rethrow(failure);
        }

        private synchronized boolean isTerminal() {
            return terminal;
        }

        private void clearHostReferences() {
            delegate = null;
            cancellation = null;
        }
    }

    private static void cancelFuture(Future<?> future) {
        Throwable failure = null;
        try {
            if (!future.cancel(false) && !future.isDone()) {
                failure = new IllegalStateException("plugin runtime task cancellation was refused");
            }
        } catch (Throwable cancellationFailure) {
            failure = cancellationFailure;
        }
        rethrow(failure);
    }

    private static Throwable mergeFailure(Throwable current, Throwable failure) {
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

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void addSuppressedSafely(Throwable target, Throwable failure) {
        if (target == failure) {
            return;
        }
        try {
            target.addSuppressed(failure);
        } catch (Throwable ignored) {
            // 诊断附加失败不得覆盖主失败对象。
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("plugin runtime task cancellation failed", failure);
    }

    private static String requirePluginId(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        if (pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId must not be blank");
        }
        return pluginId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
