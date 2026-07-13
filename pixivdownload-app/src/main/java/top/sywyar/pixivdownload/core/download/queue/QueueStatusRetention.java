package top.sywyar.pixivdownload.core.download.queue;

import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** 把短期状态保留任务也纳入队列 generation；quiesce 会取消父调度器句柄并清掉插件 cleanup delegate。 */
public final class QueueStatusRetention {

    private QueueStatusRetention() {
    }

    public static void schedule(QueueTaskTracker tracker,
                                @Nullable String ownerKey,
                                TaskScheduler scheduler,
                                Instant cleanupAt,
                                Runnable cleanup) {
        Objects.requireNonNull(tracker, "tracker");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(cleanupAt, "cleanupAt");
        Objects.requireNonNull(cleanup, "cleanup");

        CleanupOnce cleanupOnce = new CleanupOnce(cleanup);
        QueueTaskTracker.Task task;
        try {
            task = tracker.prepareQueued(ownerKey);
        } catch (QueueNotAcceptingException ignored) {
            cleanupOnce.run();
            return;
        }

        ScheduledHandle handle = new ScheduledHandle();
        try {
            task.onCancellation(handle::cancel);
            task.bind(cleanupOnce);
            ScheduledFuture<?> future = scheduler.schedule(task, cleanupAt);
            if (future == null) {
                settleFailedSubmission(task, cleanupOnce, null);
                return;
            }
            handle.bind(future);
        } catch (QueueNotAcceptingException ignored) {
            cleanupOnce.run();
        } catch (Throwable failure) {
            settleFailedSubmission(task, cleanupOnce, failure);
        }
    }

    private static void settleFailedSubmission(
            QueueTaskTracker.Task task, CleanupOnce cleanup, Throwable failure) {
        try {
            task.rejectSubmission();
        } catch (Throwable rejectFailure) {
            failure = mergeFailure(failure, rejectFailure);
        }
        try {
            cleanup.run();
        } catch (Throwable cleanupFailure) {
            failure = mergeFailure(failure, cleanupFailure);
        }
        if (failure instanceof Error error) {
            throw error;
        }
        // 调度器普通拒绝维持 best-effort 状态清理语义；任务计数与 cleanup 已完成。
    }

    private static Throwable mergeFailure(Throwable current, Throwable failure) {
        if (current == null) {
            return failure;
        }
        if (failureRank(failure) > failureRank(current)) {
            addSuppressedSafely(failure, current);
            return failure;
        }
        addSuppressedSafely(current, failure);
        return current;
    }

    private static int failureRank(Throwable failure) {
        if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) {
            return 2;
        }
        return failure instanceof Error ? 1 : 0;
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

    private static final class CleanupOnce implements Runnable {
        private final AtomicBoolean invoked = new AtomicBoolean();
        private Runnable delegate;

        private CleanupOnce(Runnable delegate) {
            this.delegate = delegate;
        }

        @Override
        public void run() {
            if (!invoked.compareAndSet(false, true)) {
                return;
            }
            Runnable action = delegate;
            delegate = null;
            action.run();
        }
    }

    /** cancel/bind 可任意先后；一旦两者都发生就取消 ScheduledFuture。 */
    private static final class ScheduledHandle {

        private ScheduledFuture<?> future;
        private boolean cancellationRequested;

        synchronized void bind(ScheduledFuture<?> scheduledFuture) {
            future = scheduledFuture;
            if (cancellationRequested) {
                future.cancel(false);
            }
        }

        synchronized void cancel() {
            cancellationRequested = true;
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}
