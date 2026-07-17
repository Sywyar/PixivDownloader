package top.sywyar.pixivdownload.plugin.runtime.download.queue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.TaskScheduler;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("下载状态延迟清理句柄")
class QueueStatusRetentionTest {

    private ThreadPoolTaskScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("quiesce 会取消五分钟清理句柄并从父调度队列移除")
    void cancelsAndRemovesScheduledCleanup() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        AtomicBoolean cleanupRan = new AtomicBoolean();

        QueueStatusRetention.schedule(tracker, "owner-a", scheduler,
                Instant.now().plusSeconds(300), () -> cleanupRan.set(true));
        assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).hasSize(1);

        QueueGenerationDrain drain = tracker.prepareQuiesce();
        tracker.cancelQuiescedTasks();

        assertThat(drain.isDrained()).isTrue();
        assertThat(cleanupRan).isFalse();
        assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).isEmpty();
    }

    @Test
    @DisplayName("schedule 返回与 handle bind 之间 quiesce 仍会取消真实 future")
    void cancelsFutureWhenQuiesceWinsBeforeHandleBind() throws Exception {
        TaskScheduler blockingScheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        CountDownLatch scheduleEntered = new CountDownLatch(1);
        CountDownLatch returnFuture = new CountDownLatch(1);
        when(blockingScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            scheduleEntered.countDown();
            returnFuture.await();
            return future;
        });
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        AtomicBoolean cleanupRan = new AtomicBoolean();
        Thread submitter = new Thread(() -> QueueStatusRetention.schedule(
                tracker, null, blockingScheduler, Instant.now().plusSeconds(300),
                () -> cleanupRan.set(true)));
        submitter.start();
        assertThat(scheduleEntered.await(2, TimeUnit.SECONDS)).isTrue();

        QueueGenerationDrain drain = tracker.prepareQuiesce();
        tracker.cancelQuiescedTasks();
        returnFuture.countDown();
        submitter.join(2_000);

        assertThat(submitter.isAlive()).isFalse();
        assertThat(drain.isDrained()).isTrue();
        assertThat(cleanupRan).isFalse();
        org.mockito.Mockito.verify(future).cancel(false);
    }

    @Test
    @DisplayName("ScheduledFuture cancel fatal 原样抛出但 drain 已取得且 delegate 已清除")
    void preservesDrainAndClearsDelegateWhenFutureCancellationThrowsFatal() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        ProbeVmError fatal = new ProbeVmError();
        when(future.cancel(false)).thenThrow(fatal);
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        AtomicBoolean cleanupRan = new AtomicBoolean();
        AtomicReference<Runnable> wrapper = new AtomicReference<>();
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            wrapper.set(invocation.getArgument(0));
            return future;
        });
        QueueStatusRetention.schedule(tracker, null, taskScheduler, Instant.now().plusSeconds(300),
                () -> cleanupRan.set(true));

        QueueGenerationDrain drain = tracker.prepareQuiesce();
        assertThatThrownBy(tracker::cancelQuiescedTasks).isSameAs(fatal);
        wrapper.get().run();

        assertThat(drain.isDrained()).isTrue();
        assertThat(cleanupRan).isFalse();
        assertThat(tracker.prepareQuiesce()).isSameAs(drain);
    }

    @Test
    @DisplayName("调度器致命失败时仍释放任务并恰好一次清理，普通清理失败仅作 suppressed")
    void schedulerFatalKeepsIdentityAfterBestEffortCleanup() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ProbeVmError fatal = new ProbeVmError();
        IllegalStateException cleanupFailure = new IllegalStateException("cleanup-failed");
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenThrow(fatal);
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        AtomicInteger cleanupCalls = new AtomicInteger();

        assertThatThrownBy(() -> QueueStatusRetention.schedule(
                tracker, null, taskScheduler, Instant.now().plusSeconds(300), () -> {
                    cleanupCalls.incrementAndGet();
                    throw cleanupFailure;
                })).isSameAs(fatal);

        assertThat(cleanupCalls).hasValue(1);
        assertThat(fatal.getSuppressed()).contains(cleanupFailure);
        assertThat(tracker.activeTaskCount()).isZero();
    }

    @Test
    @DisplayName("调度器断言错误之后清理虚拟机错误升级为主失败")
    void laterCleanupVmErrorTakesPriorityOverAssertionError() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        AssertionError schedulerFailure = new AssertionError("scheduler-assertion");
        ProbeVmError cleanupFatal = new ProbeVmError();
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(schedulerFailure);
        QueueTaskTracker tracker = new QueueTaskTracker("probe");
        AtomicInteger cleanupCalls = new AtomicInteger();

        assertThatThrownBy(() -> QueueStatusRetention.schedule(
                tracker, null, taskScheduler, Instant.now().plusSeconds(300), () -> {
                    cleanupCalls.incrementAndGet();
                    throw cleanupFatal;
                })).isSameAs(cleanupFatal);

        assertThat(cleanupFatal.getSuppressed()).contains(schedulerFailure);
        assertThat(cleanupCalls).hasValue(1);
        assertThat(tracker.activeTaskCount()).isZero();
    }

    private static final class ProbeVmError extends VirtualMachineError {
    }
}
