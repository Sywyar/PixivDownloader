package top.sywyar.pixivdownload.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkKind;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ScheduleRunner 宿主 owner 租约")
class ScheduleRunnerLeaseTest {

    @Test
    @DisplayName("宿主 publication 尚未发布或已撤回时 tick 不读取到期任务")
    void tickDoesNotReadTasksWithoutHostPublication() {
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        ScheduleRunner runner = new ScheduleRunner(
                store,
                mock(ScheduleExecutor.class),
                new ScheduleConfig(),
                new ScheduleRunState(),
                new ScheduleCapabilityRegistry());

        runner.tick();

        verify(store, never()).findDue(anyLong());
    }

    @Test
    @DisplayName("tick 从读取任务到退出全程持有宿主 owner lease")
    void tickKeepsHostLeaseUntilInvocationReturns() throws Exception {
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        CountDownLatch lookupStarted = new CountDownLatch(1);
        CountDownLatch allowLookup = new CountDownLatch(1);
        when(store.findDue(anyLong())).thenAnswer(invocation -> {
            lookupStarted.countDown();
            assertThat(allowLookup.await(5, TimeUnit.SECONDS)).isTrue();
            return List.of();
        });
        ScheduledWorkRunner workRunner = mock(ScheduledWorkRunner.class);
        when(workRunner.kind()).thenReturn(ScheduledWorkKind.ILLUST);
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        var publication = ScheduleCapabilityTestFixture.publishDownloadWorkbench(
                registry, List.of(workRunner));
        ScheduleRunner runner = new ScheduleRunner(
                store,
                mock(ScheduleExecutor.class),
                new ScheduleConfig(),
                new ScheduleRunState(),
                registry);
        Thread tick = new Thread(runner::tick, "schedule-runner-lease-test");
        tick.start();
        try {
            assertThat(lookupStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var drain = ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow();
            assertThat(drain.activeLeaseCount()).isEqualTo(1);
            assertThat(drain.awaitDrained(
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50))).isFalse();

            allowLookup.countDown();
            tick.join(5_000L);

            assertThat(tick.isAlive()).isFalse();
            assertThat(drain.awaitDrained(
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(1))).isTrue();
        } finally {
            allowLookup.countDown();
            tick.join(5_000L);
        }
    }

    @Test
    @DisplayName("due 快照后被禁用或版本变化时 durable CAS 拒绝并清理内存 claim")
    void durableClaimRejectsStaleOrDisabledDueSnapshot() {
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        ScheduleExecutor executor = mock(ScheduleExecutor.class);
        ScheduleRunState runState = new ScheduleRunState();
        var task = mock(top.sywyar.pixivdownload.core.schedule.ScheduledTask.class);
        when(task.id()).thenReturn(31L);
        when(task.stateVersion()).thenReturn(7L);
        when(store.findDue(anyLong())).thenReturn(List.of(task));
        when(store.tryQueueDue(eq(31L), eq(7L), anyString(), anyLong()))
                .thenReturn(Optional.empty());

        ScheduledWorkRunner workRunner = mock(ScheduledWorkRunner.class);
        when(workRunner.kind()).thenReturn(ScheduledWorkKind.ILLUST);
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityTestFixture.publishDownloadWorkbench(registry, List.of(workRunner));
        ScheduleRunner runner = new ScheduleRunner(
                store, executor, new ScheduleConfig(), runState, registry);

        runner.tick();

        verify(store).tryQueueDue(eq(31L), eq(7L), anyString(), anyLong());
        verify(store, never()).releaseQueued(anyLong(), any(), any());
        verify(executor, never()).runTaskAndRecord(any(), any(), any());
        assertThat(runState.get(31L)).isNull();
    }

    @Test
    @DisplayName("批量预认领中途异常会收敛当前不确定 claim 并释放此前排队任务")
    void batchClaimFailureCleansCurrentAndPreviouslyQueuedClaims() {
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        ScheduleExecutor executor = mock(ScheduleExecutor.class);
        ScheduleRunState runState = new ScheduleRunState();
        var first = mock(top.sywyar.pixivdownload.core.schedule.ScheduledTask.class);
        when(first.id()).thenReturn(32L);
        when(first.stateVersion()).thenReturn(7L);
        when(first.nextRunTime()).thenReturn(8_000L);
        var second = mock(top.sywyar.pixivdownload.core.schedule.ScheduledTask.class);
        when(second.id()).thenReturn(33L);
        when(second.stateVersion()).thenReturn(9L);
        when(second.nextRunTime()).thenReturn(9_000L);
        when(store.findAll()).thenReturn(List.of());
        when(store.findDue(anyLong())).thenReturn(List.of(first, second));
        ScheduleRunToken firstToken = new ScheduleRunToken(
                "claim-first-batch", 8L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        when(store.tryQueueDue(eq(32L), eq(7L), anyString(), anyLong()))
                .thenReturn(Optional.of(firstToken));
        AtomicReference<String> uncertainClaim = new AtomicReference<>();
        when(store.tryQueueDue(eq(33L), eq(9L), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    uncertainClaim.set(invocation.getArgument(2));
                    throw new IllegalStateException("claim write failed");
                });

        ScheduledWorkRunner workRunner = mock(ScheduledWorkRunner.class);
        when(workRunner.kind()).thenReturn(ScheduledWorkKind.ILLUST);
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityTestFixture.publishDownloadWorkbench(registry, List.of(workRunner));
        ScheduleRunner runner = new ScheduleRunner(
                store, executor, new ScheduleConfig(), runState, registry);

        runner.tick();

        verify(executor).releaseClaim(33L, uncertainClaim.get(), 9_000L);
        verify(executor).releaseQueued(32L, firstToken);
        verify(executor, never()).runTaskAndRecord(any(), any(), any());
        assertThat(runState.get(32L)).isNull();
        assertThat(runState.get(33L)).isNull();
    }

    @Test
    @DisplayName("宿主取消发生在 durable queue 后时释放队列 token 且不进入执行器")
    void hostCancellationReleasesDurablyQueuedTask() throws Exception {
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        ScheduleExecutor executor = mock(ScheduleExecutor.class);
        ScheduleRunState runState = new ScheduleRunState();
        var task = mock(top.sywyar.pixivdownload.core.schedule.ScheduledTask.class);
        when(task.id()).thenReturn(41L);
        when(task.stateVersion()).thenReturn(12L);
        when(task.nextRunTime()).thenReturn(8_000L);
        when(store.findDue(anyLong())).thenReturn(List.of(task));

        ScheduledWorkRunner workRunner = mock(ScheduledWorkRunner.class);
        when(workRunner.kind()).thenReturn(ScheduledWorkKind.ILLUST);
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        var publication = ScheduleCapabilityTestFixture.publishDownloadWorkbench(
                registry, List.of(workRunner));
        ScheduleRunToken queuedToken = new ScheduleRunToken(
                "claim-host-cancel", 13L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        AtomicReference<top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain> drain =
                new AtomicReference<>();
        when(store.tryQueueDue(eq(41L), eq(12L), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    drain.set(ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow());
                    return Optional.of(queuedToken);
                });
        ScheduleRunner runner = new ScheduleRunner(
                store, executor, new ScheduleConfig(), runState, registry);

        runner.tick();

        verify(executor).releaseQueued(41L, queuedToken);
        verify(executor, never()).runTaskAndRecord(any(), any(), any());
        assertThat(runState.get(41L)).isNull();
        assertThat(drain.get()).isNotNull();
        assertThat(drain.get().awaitDrained(
                System.nanoTime() + TimeUnit.SECONDS.toNanos(1))).isTrue();
    }

    @Test
    @DisplayName("排队任务等待前序任务时被人工挂起会用原 token 完成取消")
    void queuedTaskSuspendedBehindPreviousTaskFinishesCancellation() {
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        ScheduleRunState runState = new ScheduleRunState();
        var first = mock(top.sywyar.pixivdownload.core.schedule.ScheduledTask.class);
        when(first.id()).thenReturn(51L);
        when(first.stateVersion()).thenReturn(10L);
        var waiting = mock(top.sywyar.pixivdownload.core.schedule.ScheduledTask.class);
        when(waiting.id()).thenReturn(52L);
        when(waiting.stateVersion()).thenReturn(20L);
        when(waiting.nextRunTime()).thenReturn(9_000L);
        when(store.findDue(anyLong())).thenReturn(List.of(first, waiting));

        ScheduleRunToken firstToken = new ScheduleRunToken(
                "claim-first", 11L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        ScheduleRunToken waitingToken = new ScheduleRunToken(
                "claim-waiting", 21L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        when(store.tryQueueDue(eq(51L), eq(10L), anyString(), anyLong()))
                .thenReturn(Optional.of(firstToken));
        when(store.tryQueueDue(eq(52L), eq(20L), anyString(), anyLong()))
                .thenReturn(Optional.of(waitingToken));

        var cancelled = mock(top.sywyar.pixivdownload.core.schedule.ScheduledTask.class);
        when(cancelled.nextRunTime()).thenReturn(9_000L);
        when(cancelled.runState()).thenReturn(
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.CANCEL_REQUESTED);
        when(cancelled.runClaimToken()).thenReturn(waitingToken.claimToken());
        when(cancelled.stateVersion()).thenReturn(22L);
        when(cancelled.suspendReason()).thenReturn(ScheduleSuspendReason.MANUAL);
        when(cancelled.suspendCode()).thenReturn("admin.pause");
        when(cancelled.suspendDetailJson()).thenReturn("{\"by\":\"admin\"}");
        when(store.findById(52L)).thenReturn(cancelled);
        when(store.releaseQueued(52L, waitingToken, null)).thenReturn(OptionalLong.empty());
        ScheduleRunToken cancelledToken = new ScheduleRunToken(
                waitingToken.claimToken(), 22L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.CANCEL_REQUESTED);
        when(store.finishCancelled(eq(52L), eq(cancelledToken), eq(ScheduleLastOutcome.INTERRUPTED),
                anyLong(), eq("CLAIM_ABANDONED"), isNull(), eq(9_000L)))
                .thenReturn(OptionalLong.of(23L));

        ScheduledWorkRunner workRunner = mock(ScheduledWorkRunner.class);
        when(workRunner.kind()).thenReturn(ScheduledWorkKind.ILLUST);
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityTestFixture.publishDownloadWorkbench(registry, List.of(workRunner));
        AtomicBoolean previousFinished = new AtomicBoolean();
        when(store.startRun(52L, waitingToken)).thenAnswer(invocation -> {
            assertThat(previousFinished).isTrue();
            return Optional.empty();
        });
        ScheduleExecutor executor = new ScheduleExecutor(
                store, registry, null, null, null, null, null,
                new ScheduleConfig(), runState, null, null, null, null, null, null, null,
                null, null) {
            @Override
            void runTaskAndRecord(
                    top.sywyar.pixivdownload.core.schedule.ScheduledTask task,
                    ScheduleRunState.Claim claim,
                    ScheduleRunToken queuedToken) {
                if (task.id() == 51L) {
                    assertThat(runState.markRunning(claim)).isTrue();
                    runState.clear(claim);
                    previousFinished.set(true);
                    return;
                }
                super.runTaskAndRecord(task, claim, queuedToken);
            }
        };
        ScheduleRunner runner = new ScheduleRunner(
                store, executor, new ScheduleConfig(), runState, registry);

        runner.tick();

        verify(store).startRun(52L, waitingToken);
        verify(store).releaseQueued(52L, waitingToken, null);
        verify(store).finishCancelled(eq(52L), eq(cancelledToken), eq(ScheduleLastOutcome.INTERRUPTED),
                anyLong(), eq("CLAIM_ABANDONED"), isNull(), eq(9_000L));
        assertThat(cancelled.suspendReason()).isEqualTo(ScheduleSuspendReason.MANUAL);
        assertThat(runState.get(51L)).isNull();
        assertThat(runState.get(52L)).isNull();
    }

    @Test
    @DisplayName("tick 会收敛数据库仍在途但本进程已无镜像的孤儿认领")
    void tickRecoversDurableClaimMissingFromLocalState() {
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        ScheduleExecutor executor = mock(ScheduleExecutor.class);
        ScheduleRunState runState = new ScheduleRunState();
        var orphan = mock(top.sywyar.pixivdownload.core.schedule.ScheduledTask.class);
        when(orphan.id()).thenReturn(61L);
        when(orphan.runState()).thenReturn(
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.RUNNING);
        when(store.findAll()).thenReturn(List.of(orphan));
        when(store.findDue(anyLong())).thenReturn(List.of());

        ScheduledWorkRunner workRunner = mock(ScheduledWorkRunner.class);
        when(workRunner.kind()).thenReturn(ScheduledWorkKind.ILLUST);
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityTestFixture.publishDownloadWorkbench(registry, List.of(workRunner));
        ScheduleRunner runner = new ScheduleRunner(
                store, executor, new ScheduleConfig(), runState, registry);

        runner.tick();

        verify(executor).recoverOrphanedClaim(orphan);
    }
}
