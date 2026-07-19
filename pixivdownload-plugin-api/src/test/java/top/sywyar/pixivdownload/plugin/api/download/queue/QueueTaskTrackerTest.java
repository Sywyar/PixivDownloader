package top.sywyar.pixivdownload.plugin.api.download.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("下载队列任务精确排空")
class QueueTaskTrackerTest {

    @Test
    @DisplayName("状态尚未发布的排队任务也能取消且清掉 delegate")
    void cancelsQueuedTaskBeforeStatusPublication() {
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        QueueTaskTracker.Task task = tracker.prepareQueued("owner-a");
        AtomicBoolean ran = new AtomicBoolean();
        AtomicBoolean cancellationObservedAfterwards = new AtomicBoolean();
        task.bind(() -> ran.set(true));

        QueueGenerationDrain drain = tracker.prepareQuiesce();
        tracker.cancelQuiescedTasks();
        task.onCancellation(() -> cancellationObservedAfterwards.set(true));
        task.run();

        assertThat(drain.isDrained()).isTrue();
        assertThat(drain.activeCount()).isZero();
        assertThat(ran).isFalse();
        assertThat(cancellationObservedAfterwards).isTrue();
    }

    @Test
    @DisplayName("运行任务收到取消后必须等 finally 退出才归零")
    void waitsForRunningTaskToActuallyExit() throws Exception {
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        QueueTaskTracker.Task task = tracker.prepareQueued("owner-a");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean cancellationObserved = new AtomicBoolean();
        task.onCancellation(() -> cancellationObserved.set(true));
        task.bind(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        Thread worker = new Thread(task, "queue-tracker-test");
        worker.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        QueueGenerationDrain drain = tracker.prepareQuiesce();
        tracker.cancelQuiescedTasks();

        assertThat(cancellationObserved).isTrue();
        assertThat(drain.activeCount()).isEqualTo(1);
        assertThat(drain.awaitDrained(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20))).isFalse();

        release.countDown();
        worker.join(2_000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(1))).isTrue();
    }

    @Test
    @DisplayName("执行器拒绝和任务异常都归还精确计数")
    void releasesAfterExecutorRejectionAndTaskFailure() {
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        QueueTaskTracker.Task rejected = tracker.prepareQueued(null);
        rejected.bind(() -> { });
        rejected.rejectSubmission();
        assertThat(tracker.activeTaskCount()).isZero();

        QueueTaskTracker.Task failed = tracker.prepareQueued(null);
        IllegalStateException expected = new IllegalStateException("boom");
        failed.bind(() -> { throw expected; });
        assertThatThrownBy(failed::run).isSameAs(expected);
        assertThat(tracker.activeTaskCount()).isZero();
    }

    @Test
    @DisplayName("普通 clear 取消当前任务但不会永久停止接收")
    void ordinaryClearDoesNotQuiesceTracker() {
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        QueueTaskTracker.Task first = tracker.prepareQueued("owner-a");
        first.bind(() -> { });

        assertThat(tracker.cancelActive()).isEqualTo(1);
        assertThat(tracker.isAccepting()).isTrue();

        QueueTaskTracker.Task second = tracker.prepareQueued("owner-b");
        second.bind(() -> { });
        second.run();
        assertThat(tracker.activeTaskCount()).isZero();
    }

    @Test
    @DisplayName("取消 callback 的 fatal 不会让唯一 drain 丢失且保持对象身份")
    void preservesDrainWhenCancellationCallbackThrowsFatal() {
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        QueueTaskTracker.Task task = tracker.prepareQueued(null);
        ProbeVmError fatal = new ProbeVmError();
        task.onCancellation(() -> { throw fatal; });
        task.bind(() -> { });

        QueueGenerationDrain drain = tracker.prepareQuiesce();
        assertThatThrownBy(tracker::cancelQuiescedTasks).isSameAs(fatal);

        assertThat(tracker.prepareQuiesce()).isSameAs(drain);
        assertThat(drain.isDrained()).isTrue();
        assertThatCode(tracker::cancelQuiescedTasks).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("多个取消 callback 中后到 fatal 升级为主失败且其余任务仍清退")
    void laterFatalCancellationTakesPriorityAndCleanupContinues() {
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        IllegalStateException ordinary = new IllegalStateException("ordinary-first");
        ProbeVmError fatal = new ProbeVmError();
        IllegalArgumentException later = new IllegalArgumentException("ordinary-after-fatal");
        AtomicBoolean thirdCalled = new AtomicBoolean();
        QueueTaskTracker.Task first = tracker.prepareQueued("first");
        QueueTaskTracker.Task second = tracker.prepareQueued("second");
        QueueTaskTracker.Task third = tracker.prepareQueued("third");
        first.onCancellation(() -> { throw ordinary; });
        second.onCancellation(() -> { throw fatal; });
        third.onCancellation(() -> {
            thirdCalled.set(true);
            throw later;
        });
        first.bind(() -> { });
        second.bind(() -> { });
        third.bind(() -> { });

        QueueGenerationDrain drain = tracker.prepareQuiesce();
        assertThatThrownBy(tracker::cancelQuiescedTasks).isSameAs(fatal);

        assertThat(thirdCalled).isTrue();
        assertThat(fatal.getSuppressed()).contains(ordinary, later);
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("acquire 与 prepareQuiesce 并发时只有一个线性化结果")
    void linearizesAcquireAgainstPrepareQuiesce() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            QueueTaskTracker tracker = new QueueTaskTracker("probe");
            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicReference<QueueTaskTracker.Task> acquired = new AtomicReference<>();
            AtomicReference<Throwable> acquireFailure = new AtomicReference<>();
            Thread acquirer = new Thread(() -> {
                try {
                    barrier.await();
                    acquired.set(tracker.beginRunning("owner-a"));
                } catch (Throwable failure) {
                    acquireFailure.set(failure);
                }
            });
            acquirer.start();
            barrier.await();
            QueueGenerationDrain drain = tracker.prepareQuiesce();
            acquirer.join(2_000);
            tracker.cancelQuiescedTasks();

            QueueTaskTracker.Task task = acquired.get();
            if (task == null) {
                assertThat(acquireFailure.get()).isInstanceOf(QueueNotAcceptingException.class);
                assertThat(drain.isDrained()).isTrue();
            } else {
                assertThat(task.isCancellationRequested()).isTrue();
                assertThat(drain.activeCount()).isEqualTo(1);
                task.completeRunning();
                assertThat(drain.isDrained()).isTrue();
            }
            assertThatThrownBy(() -> tracker.prepareQueued("late"))
                    .isInstanceOf(QueueNotAcceptingException.class)
                    .satisfies(error -> assertThat(((QueueNotAcceptingException) error).queueType())
                            .isEqualTo("probe"));
        }
    }

    @Test
    @DisplayName("running permit 在 handoff 前取消时不得提交 delegate")
    void cancelsBeforeRunningHandoff() {
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        QueueTaskTracker.Task task = tracker.beginRunning("owner-a");
        AtomicBoolean ran = new AtomicBoolean();
        QueueGenerationDrain drain = tracker.prepareQuiesce();
        tracker.cancelQuiescedTasks();

        assertThat(task.handoff(() -> ran.set(true))).isFalse();
        task.run();
        assertThat(ran).isFalse();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("running permit handoff 后执行前取消应清掉 delegate")
    void cancelsAfterHandoffBeforeExecutorRun() {
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        QueueTaskTracker.Task task = tracker.beginRunning("owner-a");
        AtomicBoolean ran = new AtomicBoolean();
        assertThat(task.handoff(() -> ran.set(true))).isTrue();

        QueueGenerationDrain drain = tracker.prepareQuiesce();
        tracker.cancelQuiescedTasks();
        task.run();

        assertThat(ran).isFalse();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("按 owner clear 只取消匹配任务")
    void cancelsOnlyMatchingOwner() {
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        QueueTaskTracker.Task ownerA = tracker.prepareQueued("owner-a");
        QueueTaskTracker.Task ownerB = tracker.prepareQueued("owner-b");
        AtomicBoolean ownerBRan = new AtomicBoolean();
        ownerA.bind(() -> { });
        ownerB.bind(() -> ownerBRan.set(true));

        assertThat(tracker.cancelForOwner("owner-a")).isEqualTo(1);
        ownerA.run();
        ownerB.run();

        assertThat(ownerBRan).isTrue();
        assertThat(tracker.activeTaskCount()).isZero();
        assertThat(tracker.isAccepting()).isTrue();
    }

    @Test
    @DisplayName("按 owner 匹配器清退排队和运行任务且保留其它任务")
    void cancelsMatchingQueuedAndRunningTasks() {
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        QueueTaskTracker.Task queuedDownload = tracker.prepareQueued("download:owner-a");
        QueueTaskTracker.Task runningDownload = tracker.beginRunning("download:owner-b");
        QueueTaskTracker.Task queuedUnowned = tracker.prepareQueued(null);
        QueueTaskTracker.Task queuedTranslation = tracker.prepareQueued("translation:novel-1");
        AtomicBoolean queuedDownloadRan = new AtomicBoolean();
        AtomicBoolean queuedUnownedRan = new AtomicBoolean();
        AtomicBoolean runningCancellationObserved = new AtomicBoolean();
        AtomicBoolean translationRan = new AtomicBoolean();
        queuedDownload.bind(() -> queuedDownloadRan.set(true));
        queuedUnowned.bind(() -> queuedUnownedRan.set(true));
        runningDownload.onCancellation(() -> runningCancellationObserved.set(true));
        queuedTranslation.bind(() -> translationRan.set(true));

        assertThat(tracker.cancelMatchingOwners(owner -> owner == null || owner.startsWith("download:")))
                .isEqualTo(3);

        assertThat(queuedDownloadRan).isFalse();
        assertThat(queuedUnownedRan).isFalse();
        assertThat(runningCancellationObserved).isTrue();
        assertThat(tracker.activeTaskCount()).isEqualTo(2);
        assertThat(tracker.isAccepting()).isTrue();

        queuedDownload.run();
        queuedUnowned.run();
        queuedTranslation.run();
        runningDownload.completeRunning();

        assertThat(queuedDownloadRan).isFalse();
        assertThat(queuedUnownedRan).isFalse();
        assertThat(translationRan).isTrue();
        assertThat(tracker.activeTaskCount()).isZero();
    }

    @Test
    @DisplayName("owner 匹配器失败时不得部分取消任务")
    void leavesTasksUntouchedWhenOwnerMatcherFails() {
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        QueueTaskTracker.Task first = tracker.prepareQueued("download:owner-a");
        QueueTaskTracker.Task second = tracker.prepareQueued("download:owner-b");
        AtomicBoolean firstCancelled = new AtomicBoolean();
        AtomicBoolean secondCancelled = new AtomicBoolean();
        AtomicInteger executed = new AtomicInteger();
        first.onCancellation(() -> firstCancelled.set(true));
        second.onCancellation(() -> secondCancelled.set(true));
        first.bind(executed::incrementAndGet);
        second.bind(executed::incrementAndGet);
        IllegalStateException expected = new IllegalStateException("matcher failed");

        assertThatThrownBy(() -> tracker.cancelMatchingOwners(owner -> {
            if (owner.endsWith("owner-b")) {
                throw expected;
            }
            return true;
        })).isSameAs(expected);

        assertThat(firstCancelled).isFalse();
        assertThat(secondCancelled).isFalse();
        assertThat(tracker.activeTaskCount()).isEqualTo(2);
        first.run();
        second.run();
        assertThat(executed).hasValue(2);
        assertThat(tracker.activeTaskCount()).isZero();
    }

    @Test
    @DisplayName("owner 匹配器在 tracker 状态锁外执行")
    void evaluatesOwnerMatcherOutsideTrackerLock() throws Exception {
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        QueueTaskTracker.Task initial = tracker.prepareQueued("download:owner-a");
        initial.bind(() -> { });
        CountDownLatch matcherEntered = new CountDownLatch(1);
        CountDownLatch lateTaskAcquired = new CountDownLatch(1);
        AtomicInteger cancelled = new AtomicInteger(-1);
        AtomicReference<QueueTaskTracker.Task> lateTask = new AtomicReference<>();
        AtomicReference<Throwable> clearFailure = new AtomicReference<>();
        Thread clearer = new Thread(() -> {
            try {
                cancelled.set(tracker.cancelMatchingOwners(owner -> {
                    matcherEntered.countDown();
                    try {
                        return lateTaskAcquired.await(2, TimeUnit.SECONDS) && owner.startsWith("download:");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }));
            } catch (Throwable failure) {
                clearFailure.set(failure);
            }
        }, "queue-owner-clear-test");
        clearer.start();
        assertThat(matcherEntered.await(2, TimeUnit.SECONDS)).isTrue();

        Thread acquirer = new Thread(() -> {
            QueueTaskTracker.Task task = tracker.prepareQueued("translation:novel-1");
            task.bind(() -> { });
            lateTask.set(task);
            lateTaskAcquired.countDown();
        }, "queue-owner-acquire-test");
        acquirer.start();
        acquirer.join(2_000);
        clearer.join(2_000);

        assertThat(acquirer.isAlive()).isFalse();
        assertThat(clearer.isAlive()).isFalse();
        assertThat(clearFailure.get()).isNull();
        assertThat(cancelled).hasValue(1);
        assertThat(tracker.activeTaskCount()).isEqualTo(1);

        QueueTaskTracker.Task remaining = lateTask.get();
        assertThat(remaining).isNotNull();
        remaining.run();
        assertThat(tracker.activeTaskCount()).isZero();
    }

    @Test
    @DisplayName("owner 匹配器不能为空")
    void rejectsNullOwnerMatcher() {
        QueueTaskTracker tracker = new QueueTaskTracker("probe");

        assertThatThrownBy(() -> tracker.cancelMatchingOwners(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ownerMatcher");
    }

    @Test
    @DisplayName("owner 匹配清退与 quiesce 交错时运行任务只在真实退出后归零")
    void preservesRunningCountWhenOwnerClearOverlapsQuiesce() throws Exception {
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        QueueTaskTracker.Task running = tracker.beginRunning("download:owner-a");
        CountDownLatch matcherEntered = new CountDownLatch(1);
        CountDownLatch finishMatching = new CountDownLatch(1);
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger matched = new AtomicInteger(-1);
        AtomicReference<Throwable> clearFailure = new AtomicReference<>();
        running.onCancellation(cancellations::incrementAndGet);
        Thread clearer = new Thread(() -> {
            try {
                matched.set(tracker.cancelMatchingOwners(owner -> {
                    matcherEntered.countDown();
                    try {
                        if (!finishMatching.await(2, TimeUnit.SECONDS)) {
                            return false;
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    return true;
                }));
            } catch (Throwable failure) {
                clearFailure.set(failure);
            }
        }, "queue-owner-quiesce-test");
        clearer.start();
        assertThat(matcherEntered.await(2, TimeUnit.SECONDS)).isTrue();

        QueueGenerationDrain drain;
        try {
            drain = tracker.prepareQuiesce();
            tracker.cancelQuiescedTasks();
            assertThat(cancellations).hasValue(1);
            assertThat(drain.activeCount()).isEqualTo(1);
            assertThat(drain.isDrained()).isFalse();
        } finally {
            finishMatching.countDown();
        }
        clearer.join(2_000);
        assertThat(clearer.isAlive()).isFalse();
        assertThat(clearFailure.get()).isNull();
        assertThat(matched).hasValue(1);
        assertThat(cancellations).hasValue(1);
        assertThat(drain.activeCount()).isEqualTo(1);

        running.completeRunning();
        assertThat(drain.isDrained()).isTrue();
        assertThat(drain.activeCount()).isZero();
    }

    @Test
    @DisplayName("drain 等待中断时恢复中断标志并返回 false")
    void preservesInterruptWhileWaiting() throws Exception {
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        QueueTaskTracker.Task task = tracker.beginRunning(null);
        QueueGenerationDrain drain = tracker.prepareQuiesce();
        tracker.cancelQuiescedTasks();
        AtomicBoolean result = new AtomicBoolean(true);
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            result.set(drain.awaitDrained());
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        waiter.start();
        waiter.join(2_000);

        assertThat(result).isFalse();
        assertThat(interrupted).isTrue();
        task.completeRunning();
    }

    @Test
    @DisplayName("旧新 tracker generation 的 drain 与接收状态彼此隔离")
    void isolatesOldAndNewGenerations() {
        QueueTaskTracker oldTracker = new QueueTaskTracker("probe");
        QueueTaskTracker.Task oldTask = oldTracker.beginRunning(null);
        QueueGenerationDrain oldDrain = oldTracker.prepareQuiesce();
        oldTracker.cancelQuiescedTasks();

        QueueTaskTracker newTracker = new QueueTaskTracker("probe");
        QueueTaskTracker.Task newTask = newTracker.prepareQueued(null);
        AtomicBoolean newRan = new AtomicBoolean();
        newTask.bind(() -> newRan.set(true));
        newTask.run();
        QueueGenerationDrain newDrain = newTracker.prepareQuiesce();

        assertThat(oldDrain.generation()).isPositive();
        assertThat(newDrain.generation()).isPositive().isNotEqualTo(oldDrain.generation());
        assertThat(oldDrain.isDrained()).isFalse();
        assertThat(newRan).isTrue();
        oldTask.completeRunning();
        assertThat(oldDrain.isDrained()).isTrue();
    }

    private static final class ProbeVmError extends VirtualMachineError {
    }
}
