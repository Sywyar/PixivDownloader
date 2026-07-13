package top.sywyar.pixivdownload.core.download.queue;

import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 下载任务的精确生命周期计数器。异步调用方必须在提交父执行器之前取得 {@link Task}，并把这个宿主包装器本身
 * 交给执行器；不得把插件 lambda 直接提交到共享线程池。
 *
 * <p>取消 QUEUED 任务会原子清空 delegate 并立即归还计数，因此父执行器队列里至多残留一个不持插件引用的宿主空壳；
 * 取消 RUNNING 任务只发送协作式信号，计数必须等 {@link Task#run()} 的 {@code finally} 才归还。
 */
public final class QueueTaskTracker {

    private static final AtomicLong NEXT_GENERATION = new AtomicLong();

    private final State state;
    private final QueueGenerationDrain drain;

    public QueueTaskTracker(String queueType) {
        if (queueType == null || queueType.isBlank()) {
            throw new IllegalArgumentException("queueType must not be blank");
        }
        long generation = NEXT_GENERATION.incrementAndGet();
        this.state = new State(queueType);
        this.drain = new QueueGenerationDrain(queueType, generation, state);
    }

    /** 取得尚未提交执行器的任务包装器。取得动作与 quiesce 的停止接收动作在同一把锁上线性化。 */
    public Task prepareQueued(@Nullable String ownerKey) {
        return state.acquire(ownerKey, Task.QUEUED);
    }

    /** 取得已经在当前调用线程执行同步准备工作的任务凭据。 */
    public Task beginRunning(@Nullable String ownerKey) {
        return state.acquire(ownerKey, Task.RUNNING);
    }

    /** 原子停止接收新任务并先返回唯一 drain；此步不执行任何插件 callback。重复调用幂等。 */
    public QueueGenerationDrain prepareQuiesce() {
        state.stopAccepting();
        return drain;
    }

    /** 在调用方已经持久化 drain 后，向当前 QUEUED/RUNNING 任务发送协作式取消。重复调用幂等。 */
    public void cancelQuiescedTasks() {
        state.cancelQuiescedTasks();
    }

    /** 非生命周期调用的便利组合；生命周期必须使用 prepare/cancel 两步以免 callback fatal 丢失 drain。 */
    public QueueGenerationDrain quiesce() {
        QueueGenerationDrain prepared = prepareQuiesce();
        cancelQuiescedTasks();
        return prepared;
    }

    /** 普通“清空队列”只取消当前任务，不改变后续接收能力。 */
    public int cancelActive() {
        return state.cancelMatching(owner -> true);
    }

    /** 普通按 owner 清空只取消当前匹配任务，不改变后续接收能力。 */
    public int cancelForOwner(@Nullable String ownerKey) {
        return state.cancelMatching(owner -> Objects.equals(owner, ownerKey));
    }

    public boolean isAccepting() {
        return state.isAccepting();
    }

    public int activeTaskCount() {
        return state.activeTaskCount();
    }

    /**
     * 可提交到父执行器的宿主包装器。终态后只保留 owner 字符串和宿主计数状态，不保留插件 delegate / callback。
     */
    public static final class Task implements Runnable {

        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int TERMINAL = 2;

        private final State tracker;
        private final String ownerKey;

        private int phase;
        private boolean cancellationRequested;
        private boolean cancellationDelivered;
        private Runnable cancellationAction;
        private Runnable delegate;

        private Task(State tracker, @Nullable String ownerKey, int phase) {
            this.tracker = tracker;
            this.ownerKey = ownerKey;
            this.phase = phase;
        }

        public String ownerKey() {
            return ownerKey;
        }

        public synchronized boolean isCancellationRequested() {
            return cancellationRequested;
        }

        /**
         * 安装协作式取消动作。若取消已经发生，动作在本方法返回前执行；动作不得等待本任务自身退出。
         */
        public synchronized void onCancellation(Runnable action) {
            Objects.requireNonNull(action, "action");
            if (phase == TERMINAL && !cancellationRequested) {
                return;
            }
            if (cancellationRequested) {
                if (!cancellationDelivered) {
                    cancellationDelivered = true;
                    invokeCancellation(action);
                }
                return;
            }
            cancellationAction = action;
        }

        /**
         * 在取消信号的同一同步边界内发布状态，封住“任务已登记、状态尚未放入 map”竞态。
         */
        public synchronized boolean publishIfActive(Runnable publication) {
            Objects.requireNonNull(publication, "publication");
            if (phase == TERMINAL || cancellationRequested) {
                return false;
            }
            publication.run();
            return true;
        }

        /** 给 QUEUED 包装器绑定插件任务 delegate；绑定失败表示它已被 quiesce/clear 取消。 */
        public synchronized void bind(Runnable taskDelegate) {
            Objects.requireNonNull(taskDelegate, "taskDelegate");
            if (phase != QUEUED || cancellationRequested) {
                throw new QueueNotAcceptingException(tracker.queueType());
            }
            if (delegate != null) {
                throw new IllegalStateException("queue task delegate already bound");
            }
            delegate = taskDelegate;
        }

        /**
         * 把当前线程内的 RUNNING 准备工作无缝移交父执行器。失败时本任务已归零，调用方不得再提交。
         */
        public boolean handoff(Runnable taskDelegate) {
            Objects.requireNonNull(taskDelegate, "taskDelegate");
            boolean release = false;
            synchronized (this) {
                if (phase != RUNNING) {
                    throw new IllegalStateException("only a running queue task can be handed off");
                }
                if (cancellationRequested) {
                    phase = TERMINAL;
                    clearPluginReferences();
                    release = true;
                } else {
                    delegate = taskDelegate;
                    phase = QUEUED;
                }
            }
            if (release) {
                tracker.release(this);
                return false;
            }
            return true;
        }

        /** 父执行器拒绝提交时回滚；若 delegate 已开始，则只发取消、仍等运行线程 finally。 */
        public void rejectSubmission() {
            requestCancellation();
        }

        /** 同步准备工作正常/异常结束且没有移交执行器时归还凭据。 */
        public void completeRunning() {
            boolean release = false;
            synchronized (this) {
                if (phase == RUNNING) {
                    phase = TERMINAL;
                    clearPluginReferences();
                    release = true;
                }
            }
            if (release) {
                tracker.release(this);
            }
        }

        @Override
        public void run() {
            Runnable taskDelegate;
            synchronized (this) {
                if (phase != QUEUED) {
                    return;
                }
                phase = RUNNING;
                taskDelegate = delegate;
                delegate = null;
            }
            try {
                if (taskDelegate != null) {
                    taskDelegate.run();
                }
            } finally {
                completeRunning();
            }
        }

        private void requestCancellation() {
            boolean release = false;
            Throwable callbackFailure = null;
            synchronized (this) {
                if (phase == TERMINAL) {
                    return;
                }
                cancellationRequested = true;
                if (!cancellationDelivered && cancellationAction != null) {
                    Runnable action = cancellationAction;
                    cancellationAction = null;
                    cancellationDelivered = true;
                    try {
                        invokeCancellation(action);
                    } catch (Throwable failure) {
                        callbackFailure = failure;
                    }
                }
                if (phase == QUEUED) {
                    phase = TERMINAL;
                    clearPluginReferences();
                    release = true;
                }
            }
            if (release) {
                tracker.release(this);
            }
            rethrow(callbackFailure);
        }

        private void clearPluginReferences() {
            delegate = null;
            cancellationAction = null;
        }

        private static void invokeCancellation(Runnable action) {
            action.run();
        }

        private static void rethrow(Throwable failure) {
            if (failure == null) {
                return;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("queue cancellation callback failed", failure);
        }
    }

    /** 共享宿主状态；drain 只持有本对象。 */
    static final class State {

        private final String queueType;
        private final Set<Task> active = new LinkedHashSet<>();
        private boolean accepting = true;

        private State(String queueType) {
            this.queueType = queueType;
        }

        String queueType() {
            return queueType;
        }

        synchronized Task acquire(@Nullable String ownerKey, int phase) {
            if (!accepting) {
                throw new QueueNotAcceptingException(queueType);
            }
            Task task = new Task(this, ownerKey, phase);
            active.add(task);
            return task;
        }

        synchronized void stopAccepting() {
            accepting = false;
            if (active.isEmpty()) {
                notifyAll();
            }
        }

        void cancelQuiescedTasks() {
            List<Task> tasks;
            synchronized (this) {
                if (accepting) {
                    throw new IllegalStateException("queue drain must be prepared before cancellation: " + queueType);
                }
                tasks = new ArrayList<>(active);
            }
            cancelTasks(tasks);
        }

        int cancelMatching(Predicate<String> matcher) {
            List<Task> tasks;
            synchronized (this) {
                tasks = active.stream().filter(task -> matcher.test(task.ownerKey())).toList();
            }
            cancelTasks(tasks);
            return tasks.size();
        }

        private static void cancelTasks(List<Task> tasks) {
            Throwable first = null;
            for (Task task : tasks) {
                try {
                    task.requestCancellation();
                } catch (Throwable failure) {
                    first = mergeFailure(first, failure);
                }
            }
            rethrow(first);
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

        synchronized void release(Task task) {
            if (!active.remove(task)) {
                return;
            }
            if (active.isEmpty()) {
                notifyAll();
            }
        }

        synchronized boolean isAccepting() {
            return accepting;
        }

        synchronized boolean isDrained() {
            return active.isEmpty();
        }

        synchronized int activeTaskCount() {
            return active.size();
        }

        synchronized boolean awaitDrained(long deadlineNanos) {
            while (!active.isEmpty()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
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

        synchronized boolean awaitDrained() {
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

        private static void rethrow(Throwable failure) {
            if (failure == null) {
                return;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("queue cancellation callback failed", failure);
        }
    }
}
