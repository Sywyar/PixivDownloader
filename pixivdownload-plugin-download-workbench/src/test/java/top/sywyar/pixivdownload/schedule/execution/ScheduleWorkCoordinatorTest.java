package top.sywyar.pixivdownload.schedule.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.schedule.ScheduleRunQueue;
import top.sywyar.pixivdownload.schedule.persistence.ScheduleWorkPersistenceCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("计划任务作品协调器生命周期")
class ScheduleWorkCoordinatorTest {

    private static final String ILLUST = "illust";
    private static final String NOVEL = "novel";

    @Test
    @Timeout(5)
    @DisplayName("排队 Future 被取消后立即进入 completion、耐久 pending 且 drain 不挂起")
    void queuedCancellationCompletesAndPersistsPending() throws Exception {
        ScheduledTaskStore store = store();
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        ScheduleWorkCoordinator coordinator = coordinator(
                store,
                Map.of(ILLUST, executor(ILLUST, (work, context) -> {
                    executions.incrementAndGet();
                    return ScheduledWorkResult.completed();
                })),
                queued::set,
                5);

        coordinator.submit(work(ILLUST, "1"));

        assertThat(queued.get()).isInstanceOf(Future.class);
        assertThat(((Future<?>) queued.get()).cancel(true)).isTrue();
        assertThatThrownBy(coordinator::drain)
                .isInstanceOfSatisfying(
                        ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.work.infrastructure-cancelled"));
        assertThat(executions).hasValue(0);
        verify(store).upsertPendingWork(argThatPending(
                ILLUST, "1", "schedule.work.infrastructure-cancelled"));
    }

    @Test
    @Timeout(10)
    @DisplayName("运行中 Future 被取消后等待插件 callable 真正退出才发布 completion")
    void runningCancellationWaitsForCallableExit() throws Exception {
        ScheduledTaskStore store = store();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch pluginExited = new CountDownLatch(1);
        CountDownLatch drainFinished = new CountDownLatch(1);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        AtomicReference<Throwable> drainFailure = new AtomicReference<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService drainer = Executors.newSingleThreadExecutor();
        TaskExecutor taskExecutor = task -> {
            submitted.set(task);
            worker.execute(task);
        };
        ScheduleWorkCoordinator coordinator = coordinator(
                store,
                Map.of(ILLUST, executor(ILLUST, (work, context) -> {
                    entered.countDown();
                    try {
                        boolean released = false;
                        while (!released) {
                            try {
                                released = release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException failure) {
                                interrupted.countDown();
                            }
                        }
                        return ScheduledWorkResult.completed();
                    } finally {
                        pluginExited.countDown();
                    }
                })),
                taskExecutor,
                5);
        try {
            coordinator.submit(work(ILLUST, "2"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(((Future<?>) submitted.get()).cancel(true)).isTrue();
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();

            drainer.execute(() -> {
                try {
                    coordinator.drain();
                } catch (Throwable failure) {
                    drainFailure.set(failure);
                } finally {
                    drainFinished.countDown();
                }
            });
            assertThat(drainFinished.await(150, TimeUnit.MILLISECONDS)).isFalse();

            release.countDown();
            assertThat(pluginExited.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(drainFinished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(drainFailure.get())
                    .isInstanceOfSatisfying(
                            ScheduledExecutionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("schedule.work.infrastructure-cancelled"));
            verify(store).upsertPendingWork(argThatPending(
                    ILLUST, "2", "schedule.work.infrastructure-cancelled"));
        } finally {
            release.countDown();
            worker.shutdownNow();
            drainer.shutdownNow();
            worker.awaitTermination(5, TimeUnit.SECONDS);
            drainer.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(5)
    @DisplayName("基础设施 Future 异常先按 dispatch 元数据耐久 pending 再归零在途计数")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void exceptionalFuturePersistsPendingBeforeReleasingCapacity() {
        ScheduledTaskStore store = store();
        ScheduleWorkCoordinator coordinator = coordinator(
                store,
                Map.of(ILLUST, executor(
                        ILLUST, (work, context) -> ScheduledWorkResult.completed())),
                new SyncTaskExecutor(),
                5);
        ScheduledWork work = work(ILLUST, "3");
        FutureTask<Object> failed = new FutureTask<>(() -> {
            throw new IllegalStateException("executor infrastructure failed");
        });
        failed.run();

        BlockingQueue completions = (BlockingQueue) ReflectionTestUtils.getField(
                coordinator, "completions");
        Map inFlightWork = (Map) ReflectionTestUtils.getField(coordinator, "inFlightWork");
        Map<String, Integer> inFlightByType = (Map<String, Integer>) ReflectionTestUtils.getField(
                coordinator, "inFlightByType");
        assertThat(completions).isNotNull();
        assertThat(inFlightWork).isNotNull();
        assertThat(inFlightByType).isNotNull();
        inFlightWork.put(failed, new ScheduleWorkCoordinator.InFlightWork(work, false, ILLUST));
        inFlightByType.put(ILLUST, 1);
        ReflectionTestUtils.setField(coordinator, "inFlight", 1);
        completions.add(failed);

        assertThatThrownBy(coordinator::drain)
                .isInstanceOfSatisfying(
                        ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.work.infrastructure-failure"));
        verify(store).upsertPendingWork(argThatPending(
                ILLUST, "3", "schedule.work.infrastructure-failure"));
        assertThat(ReflectionTestUtils.getField(coordinator, "inFlight")).isEqualTo(0);
        assertThat(inFlightByType).containsEntry(ILLUST, 0);
    }

    @Test
    @DisplayName("作品 worker 的 VMError 与 ThreadDeath 在排空后原样传播并归零容量")
    void workerFatalsPropagateAfterDrainAndReleaseCapacity() throws Exception {
        List<Error> fatals = List.of(
                new TestVirtualMachineError("fixture worker vm error"),
                new ThreadDeath());
        int sequence = 0;
        for (Error fatal : fatals) {
            String workId = "fatal-" + sequence++;
            ScheduledTaskStore store = store();
            ScheduleWorkConcurrencyLimiter limiter = new ScheduleWorkConcurrencyLimiter();
            ScheduleWorkCoordinator coordinator = coordinator(
                    store,
                    Map.of(ILLUST, executor(ILLUST, (work, context) -> {
                        throw fatal;
                    })),
                    new SyncTaskExecutor(),
                    5,
                    limiter,
                    1);

            coordinator.submit(work(ILLUST, workId));
            Throwable observed = catchThrowable(coordinator::drain);

            assertThat(observed).isSameAs(fatal);
            verify(store).upsertPendingWork(argThatPending(
                    ILLUST, workId, "schedule.work.plugin-failure"));
            assertCoordinatorAccountingCleared(coordinator, limiter);
            ScheduleRunQueue.Run queue = (ScheduleRunQueue.Run) ReflectionTestUtils.getField(
                    coordinator, "runQueue");
            assertThat(queue).isNotNull();
            assertThat(queue.snapshot()).singleElement().satisfies(item -> {
                assertThat(item.getId()).isEqualTo(workId);
                assertThat(item.getStatus()).isEqualTo(ScheduleRunQueue.STATUS_FAILED);
                assertThat(item.getMessage()).isEqualTo("schedule.work.plugin-failure");
            });
        }
    }

    @Test
    @DisplayName("多个 worker fatal 按完成顺序保留首错与 suppressed 并排空全部作品")
    void multipleWorkerFatalsPreserveCompletionOrderAfterFullDrain() throws Exception {
        TestVirtualMachineError firstFatal = new TestVirtualMachineError("fixture first worker fatal");
        ThreadDeath secondFatal = new ThreadDeath();
        List<Runnable> queued = new ArrayList<>();
        ScheduledTaskStore store = store();
        ScheduleWorkConcurrencyLimiter limiter = new ScheduleWorkConcurrencyLimiter();
        ScheduleWorkCoordinator coordinator = coordinator(
                store,
                Map.of(ILLUST, executor(ILLUST, (work, context) -> {
                    if ("first".equals(work.key().id())) {
                        throw firstFatal;
                    }
                    throw secondFatal;
                })),
                queued::add,
                5,
                limiter,
                2);

        coordinator.submit(work(ILLUST, "first"));
        coordinator.submit(work(ILLUST, "second"));
        assertThat(queued).hasSize(2);
        queued.get(0).run();
        queued.get(1).run();

        Throwable observed = catchThrowable(coordinator::drain);

        assertThat(observed).isSameAs(firstFatal);
        assertThat(observed.getSuppressed()).containsExactly(secondFatal);
        verify(store, times(2)).upsertPendingWork(any());
        assertCoordinatorAccountingCleared(coordinator, limiter);
        ScheduleRunQueue.Run queue = (ScheduleRunQueue.Run) ReflectionTestUtils.getField(
                coordinator, "runQueue");
        assertThat(queue).isNotNull();
        assertThat(queue.snapshot())
                .extracting(ScheduleRunQueue.Item::getStatus)
                .containsExactly(
                        ScheduleRunQueue.STATUS_FAILED,
                        ScheduleRunQueue.STATUS_FAILED);
    }

    @Test
    @DisplayName("fatal 作品记账失败不替换 worker 首错且仅追加后续 fatal")
    void fatalCompletionRecordingCannotReplaceWorkerFatal() throws Exception {
        TestVirtualMachineError ordinaryWorkerFatal =
                new TestVirtualMachineError("fixture worker fatal with ordinary cleanup failure");
        ScheduledTaskStore ordinaryStore = store();
        when(ordinaryStore.upsertPendingWork(any()))
                .thenThrow(new IllegalStateException("fixture ordinary persistence failure"));
        ScheduleWorkConcurrencyLimiter ordinaryLimiter = new ScheduleWorkConcurrencyLimiter();
        ScheduleWorkCoordinator ordinaryCoordinator = coordinator(
                ordinaryStore,
                Map.of(ILLUST, executor(ILLUST, (work, context) -> {
                    throw ordinaryWorkerFatal;
                })),
                new SyncTaskExecutor(),
                5,
                ordinaryLimiter,
                1);

        ordinaryCoordinator.submit(work(ILLUST, "ordinary-cleanup"));
        Throwable ordinaryObserved = catchThrowable(ordinaryCoordinator::drain);

        assertThat(ordinaryObserved).isSameAs(ordinaryWorkerFatal);
        assertThat(ordinaryObserved.getSuppressed()).isEmpty();
        assertCoordinatorAccountingCleared(ordinaryCoordinator, ordinaryLimiter);

        TestVirtualMachineError fatalWorker =
                new TestVirtualMachineError("fixture worker fatal with fatal cleanup failure");
        ThreadDeath fatalCleanup = new ThreadDeath();
        ScheduledTaskStore fatalStore = store();
        when(fatalStore.upsertPendingWork(any())).thenThrow(fatalCleanup);
        ScheduleWorkConcurrencyLimiter fatalLimiter = new ScheduleWorkConcurrencyLimiter();
        ScheduleWorkCoordinator fatalCoordinator = coordinator(
                fatalStore,
                Map.of(ILLUST, executor(ILLUST, (work, context) -> {
                    throw fatalWorker;
                })),
                new SyncTaskExecutor(),
                5,
                fatalLimiter,
                1);

        fatalCoordinator.submit(work(ILLUST, "fatal-cleanup"));
        Throwable fatalObserved = catchThrowable(fatalCoordinator::drain);

        assertThat(fatalObserved).isSameAs(fatalWorker);
        assertThat(fatalObserved.getSuppressed()).containsExactly(fatalCleanup);
        assertCoordinatorAccountingCleared(fatalCoordinator, fatalLimiter);
    }

    @Test
    @DisplayName("TaskExecutor 接纳前抛 fatal 时归还 permit 且不遗留 completion")
    void taskExecutorFatalBeforeAcceptanceReleasesDispatchResources() throws Exception {
        TestVirtualMachineError fatal = new TestVirtualMachineError("fixture dispatch fatal");
        ScheduledTaskStore store = store();
        ScheduleWorkConcurrencyLimiter limiter = new ScheduleWorkConcurrencyLimiter();
        ScheduleWorkCoordinator coordinator = coordinator(
                store,
                Map.of(ILLUST, executor(
                        ILLUST, (work, context) -> ScheduledWorkResult.completed())),
                ignored -> {
                    throw fatal;
                },
                5,
                limiter,
                1);

        Throwable observed = catchThrowable(() -> coordinator.submit(work(ILLUST, "dispatch")));

        assertThat(observed).isSameAs(fatal);
        verify(store).upsertPendingWork(argThatPending(
                ILLUST, "dispatch", "schedule.work.dispatch-failed"));
        assertCoordinatorAccountingCleared(coordinator, limiter);
    }

    @Test
    @DisplayName("不同作品类型的凭证失败不会合并触发熔断")
    void credentialFailuresDoNotAccumulateAcrossWorkTypes() throws Exception {
        ScheduledTaskStore store = store();
        ScheduleWorkCoordinator coordinator = coordinator(
                store,
                Map.of(
                        ILLUST, credentialFailureExecutor(ILLUST, "fixture.illust-credential"),
                        NOVEL, credentialFailureExecutor(NOVEL, "fixture.novel-credential")),
                new SyncTaskExecutor(),
                3);

        coordinator.submit(work(ILLUST, "10"));
        coordinator.submit(work(ILLUST, "11"));
        coordinator.drain();
        coordinator.submit(work(NOVEL, "20"));
        coordinator.submit(work(NOVEL, "21"));

        assertThatCode(coordinator::drain).doesNotThrowAnyException();
        verify(store, times(4)).upsertPendingWork(any());
    }

    @Test
    @DisplayName("一种作品成功只重置自身凭证失败计数而不清除另一类型累计")
    void successResetsOnlyItsOwnCredentialFailureCount() throws Exception {
        ScheduledTaskStore store = store();
        ScheduleWorkCoordinator coordinator = coordinator(
                store,
                Map.of(
                        ILLUST, credentialFailureExecutor(ILLUST, "fixture.illust-credential"),
                        NOVEL, executor(
                                NOVEL, (work, context) -> ScheduledWorkResult.completed())),
                new SyncTaskExecutor(),
                3);

        coordinator.submit(work(ILLUST, "30"));
        coordinator.submit(work(ILLUST, "31"));
        coordinator.drain();
        coordinator.submit(work(NOVEL, "40"));
        coordinator.drain();
        coordinator.submit(work(ILLUST, "32"));

        assertThatThrownBy(coordinator::drain)
                .isInstanceOfSatisfying(
                        ScheduleCredentialCircuitOpenException.class,
                        failure -> {
                            assertThat(failure.consecutiveFailures()).isEqualTo(3);
                            assertThat(failure.lastFailureCode())
                                    .isEqualTo("fixture.illust-credential");
                        });
    }

    @Test
    @Timeout(10)
    @DisplayName("多个任务共享同一作品类型限制器时进程峰值不超过执行器上限")
    void sharedLimiterCapsConcurrencyAcrossTasks() throws Exception {
        ScheduledTaskStore store = store();
        ScheduleWorkConcurrencyLimiter limiter = new ScheduleWorkConcurrencyLimiter();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch firstWave = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ScheduledWorkExecutor executor = executor(ILLUST, (work, context) -> {
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            firstWave.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new ScheduledExecutionException(
                            ScheduledFailure.Category.INTERNAL, "fixture.release-timeout");
                }
                return ScheduledWorkResult.completed();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw ScheduledExecutionException.cancelled();
            } finally {
                active.decrementAndGet();
            }
        });
        ExecutorService workers = Executors.newFixedThreadPool(4);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        TaskExecutor taskExecutor = workers::execute;
        ScheduleWorkCoordinator first = coordinator(
                store, Map.of(ILLUST, executor), taskExecutor, 5, limiter, 2);
        ScheduleWorkCoordinator second = coordinator(
                store, Map.of(ILLUST, executor), taskExecutor, 5, limiter, 2);
        try {
            Future<?> firstTask = callers.submit(() -> {
                first.submit(work(ILLUST, "task-a-1"));
                first.submit(work(ILLUST, "task-a-2"));
                first.drain();
                return null;
            });
            Future<?> secondTask = callers.submit(() -> {
                second.submit(work(ILLUST, "task-b-1"));
                second.submit(work(ILLUST, "task-b-2"));
                second.drain();
                return null;
            });

            assertThat(firstWave.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(active).hasValue(2);
            assertThat(peak).hasValue(2);

            release.countDown();
            firstTask.get(5, TimeUnit.SECONDS);
            secondTask.get(5, TimeUnit.SECONDS);
            assertThat(peak).hasValue(2);
        } finally {
            release.countDown();
            callers.shutdownNow();
            workers.shutdownNow();
            callers.awaitTermination(5, TimeUnit.SECONDS);
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static ScheduleWorkCoordinator coordinator(
            ScheduledTaskStore store,
            Map<String, ScheduledWorkExecutor> executors,
            TaskExecutor taskExecutor,
            int credentialFailureLimit) {
        return coordinator(
                store, executors, taskExecutor, credentialFailureLimit,
                new ScheduleWorkConcurrencyLimiter(), 8);
    }

    private static ScheduleWorkCoordinator coordinator(
            ScheduledTaskStore store,
            Map<String, ScheduledWorkExecutor> executors,
            TaskExecutor taskExecutor,
            int credentialFailureLimit,
            ScheduleWorkConcurrencyLimiter concurrencyLimiter,
            int workConcurrencyLimit) {
        Map<String, Integer> limits = new LinkedHashMap<>();
        executors.keySet().forEach(workType -> limits.put(workType, workConcurrencyLimit));
        ScheduledTaskDefinition task = new ScheduledTaskDefinition(
                1L, "fixture-source", "fixture.definition", 1, "{}",
                ScheduledTaskPresentation.empty());
        return new ScheduleWorkCoordinator(
                1L,
                task,
                ScheduledNetworkRoute.direct(),
                () -> false,
                new ScheduleCredentialMaterial("fixture-secret", "fixture-reference", "account-1"),
                store,
                new ScheduleWorkPersistenceCodec(new ObjectMapper()),
                executors,
                new ScheduleRunQueue().begin(1L, ILLUST),
                taskExecutor,
                concurrencyLimiter,
                8,
                limits,
                5,
                credentialFailureLimit,
                0L,
                ignored -> {
                },
                ignored -> {
                });
    }

    private static ScheduledTaskStore store() {
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        when(store.upsertPendingWork(any())).thenReturn(1);
        return store;
    }

    private static ScheduledWorkExecutor credentialFailureExecutor(
            String workType,
            String failureCode) {
        return executor(workType, (work, context) -> {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.CREDENTIAL_INVALID, failureCode);
        });
    }

    private static ScheduledWorkExecutor executor(String workType, WorkExecution execution) {
        return new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return workType;
            }

            @Override
            public ScheduledWorkResult execute(
                    ScheduledWork work,
                    ScheduledWorkContext context) throws ScheduledExecutionException {
                return execution.execute(work, context);
            }
        };
    }

    private static ScheduledWork work(String workType, String id) {
        return new ScheduledWork(
                new ScheduledWorkKey(workType, id),
                "fixture.work", 1, "{}",
                ScheduledWorkPresentation.empty(), List.of());
    }

    private static ScheduledPendingWork argThatPending(
            String workType,
            String workId,
            String reasonCode) {
        return org.mockito.ArgumentMatchers.argThat(pending ->
                pending != null
                        && workType.equals(pending.workType())
                        && workId.equals(pending.workId())
                        && reasonCode.equals(pending.reasonCode()));
    }

    @SuppressWarnings("unchecked")
    private static void assertCoordinatorAccountingCleared(
            ScheduleWorkCoordinator coordinator,
            ScheduleWorkConcurrencyLimiter limiter) {
        assertThat(ReflectionTestUtils.getField(coordinator, "inFlight")).isEqualTo(0);
        Map<String, Integer> inFlightByType = (Map<String, Integer>) ReflectionTestUtils.getField(
                coordinator, "inFlightByType");
        Map<?, ?> inFlightWork = (Map<?, ?>) ReflectionTestUtils.getField(
                coordinator, "inFlightWork");
        BlockingQueue<?> completions = (BlockingQueue<?>) ReflectionTestUtils.getField(
                coordinator, "completions");
        Map<?, ?> limiterStates = (Map<?, ?>) ReflectionTestUtils.getField(limiter, "states");
        assertThat(inFlightByType).containsEntry(ILLUST, 0);
        assertThat(inFlightWork).isEmpty();
        assertThat(completions).isEmpty();
        assertThat(limiterStates).isEmpty();
    }

    @FunctionalInterface
    private interface WorkExecution {
        ScheduledWorkResult execute(ScheduledWork work, ScheduledWorkContext context)
                throws ScheduledExecutionException;
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
        private TestVirtualMachineError(String message) {
            super(message);
        }
    }
}
