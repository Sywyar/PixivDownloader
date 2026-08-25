package top.sywyar.pixivdownload.schedule.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialBindResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialContext;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardContext;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardResult;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledPendingReplayPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRunContext;
import top.sywyar.pixivdownload.schedule.FakeScheduleCapabilityAccess;
import top.sywyar.pixivdownload.schedule.ScheduleCapabilityTestFixture;
import top.sywyar.pixivdownload.schedule.ScheduleConfig;
import top.sywyar.pixivdownload.schedule.ScheduleDefinitionException;
import top.sywyar.pixivdownload.schedule.ScheduleRunQueue;
import top.sywyar.pixivdownload.schedule.ScheduleRunState;
import top.sywyar.pixivdownload.schedule.ScheduleSourcePublicationChangedException;
import top.sywyar.pixivdownload.schedule.ScheduleSourceUnavailableException;
import top.sywyar.pixivdownload.schedule.persistence.ScheduleWorkPersistenceCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@DisplayName("计划执行引擎取消与致命清理")
class ScheduleExecutionEngineCancellationTest extends ScheduleExecutionEngineTestSupport {

    @Test
    @DisplayName("复合租约取得后的取消在 pending 与凭证读取前生效")
    void cancellationStopsBeforePendingAndCredentialReads() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        ScheduleRunState runState = new ScheduleRunState();
        assertThat(runState.tryMarkRunning(1L)).isNotNull();
        assertThat(runState.requestCancel(1L)).isTrue();
        ScheduleExecutionEngine engine = engine(
                store,
                sourceExecutor(1, context -> ScheduledDiscoveryResult.withoutCheckpoint()),
                workExecutor(context -> ScheduledWorkResult.completed()),
                credentialPolicy(new AtomicReference<>()),
                guard(context -> ScheduledGuardDecision.proceed()),
                runState,
                new SyncTaskExecutor());

        assertThatThrownBy(() -> engine.execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.category())
                                .isEqualTo(ScheduledFailure.Category.CANCELLED));
        verify(store, never()).listPendingWork(anyLong());
        verify(store, never()).findCredentialSecret(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("owner 撤回后已排队作品在 worker 启动时取消且不调用旧插件执行器")
    void withdrawnOwnerCancelsQueuedWorkBeforePluginInvocation() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        when(store.upsertPendingWork(any())).thenReturn(1);
        AtomicInteger workExecutions = new AtomicInteger();
        AtomicReference<Runnable> queuedWork = new AtomicReference<>();
        CountDownLatch dispatched = new CountDownLatch(1);
        TaskExecutor queuedExecutor = task -> {
            if (!queuedWork.compareAndSet(null, task)) {
                throw new IllegalStateException("only one work item is expected");
            }
            dispatched.countDown();
        };
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            context.workSink().submit(work("queued-before-withdraw"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledWorkExecutor executor = workExecutor(context -> {
            workExecutions.incrementAndGet();
            return ScheduledWorkResult.completed();
        });
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        ScheduleCapabilityOwner owner = new ScheduleCapabilityOwner(
                "fixture", "fixture-package", 1L);
        ScheduledSourceDescriptor descriptor = new ScheduledSourceDescriptor(
                SOURCE, Set.of(), "fixture.definition", 1,
                new ScheduledSourcePresentation(
                        "fixture", "source.label", "source.summary", "schedule", "neutral"),
                Set.of("fixture"), Set.of(WORK), Set.of(POLICY), Set.of(GUARD), null);
        FakeScheduleCapabilityAccess.Publication publication =
                ScheduleCapabilityTestFixture.publish(
                        registry, ScheduleCapabilityTestFixture.bundle(
                                owner, List.of(descriptor), List.of(source),
                                List.of(executor),
                                List.of(credentialPolicy(new AtomicReference<>())),
                                List.of(guard(context -> ScheduledGuardDecision.proceed()))));
        ScheduleExecutionEngine engine = engine(
                store, registry, new ScheduleRunState(), queuedExecutor);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ScheduledExecutionException> execution = caller.submit(() -> {
                try {
                    engine.execute(task());
                    return null;
                } catch (ScheduledExecutionException failure) {
                    return failure;
                }
            });

            assertThat(dispatched.await(5, TimeUnit.SECONDS)).isTrue();
            FakeScheduleCapabilityAccess.Drain drain =
                    ScheduleCapabilityTestFixture.withdraw(
                            registry, publication).orElseThrow();
            assertThat(drain.activeLeaseCount()).isEqualTo(1);
            assertThat(drain.isDrained()).isFalse();

            queuedWork.get().run();

            ScheduledExecutionException failure = execution.get(5, TimeUnit.SECONDS);
            assertThat(failure).isNotNull();
            assertThat(failure.category()).isEqualTo(ScheduledFailure.Category.CANCELLED);
            assertThat(failure.code()).isEqualTo("schedule.cancelled");
            assertThat(workExecutions).hasValue(0);
            assertThat(drain.awaitDrained(
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
        } finally {
            caller.shutdownNow();
            caller.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("前一个 Guard 撤回 owner 后取消会阻止调用同轮后续 Guard")
    void ownerWithdrawalBetweenGuardsStopsLaterPluginInvocation() throws Exception {
        String firstGuardId = "fixture-guard-first";
        String secondGuardId = "fixture-guard-second";
        AtomicInteger discoveryCalls = new AtomicInteger();
        AtomicInteger secondGuardCalls = new AtomicInteger();
        AtomicReference<FakeScheduleCapabilityAccess.Publication> publication =
                new AtomicReference<>();
        AtomicReference<FakeScheduleCapabilityAccess.Drain> withdrawn = new AtomicReference<>();
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        ScheduledSourceExecutor source = new ScheduledSourceExecutor() {
            @Override
            public String sourceType() {
                return SOURCE;
            }

            @Override
            public ScheduledExecutionPlan plan(ScheduledTaskDefinition task) {
                return new ScheduledExecutionPlan(
                        Set.of(WORK), POLICY, ScheduledCredentialRequirement.REQUIRED, false,
                        List.of(
                                new ScheduledGuardBinding(
                                        firstGuardId, Set.of(ScheduledGuardPoint.RUN_START), 0),
                                new ScheduledGuardBinding(
                                        secondGuardId, Set.of(ScheduledGuardPoint.RUN_START), 0)),
                        null, 0, 1, 0L, ScheduledNetworkRoute.inherit());
            }

            @Override
            public ScheduledDiscoveryResult discover(ScheduledSourceContext context) {
                discoveryCalls.incrementAndGet();
                return ScheduledDiscoveryResult.withoutCheckpoint();
            }
        };
        ScheduledExecutionGuard firstGuard = new ScheduledExecutionGuard() {
            @Override
            public String guardId() {
                return firstGuardId;
            }

            @Override
            public ScheduledGuardResult evaluate(ScheduledGuardContext context) {
                withdrawn.set(ScheduleCapabilityTestFixture.withdraw(
                        registry, publication.get()).orElseThrow());
                return ScheduledGuardResult.decision(ScheduledGuardDecision.proceed());
            }
        };
        ScheduledExecutionGuard secondGuard = new ScheduledExecutionGuard() {
            @Override
            public String guardId() {
                return secondGuardId;
            }

            @Override
            public ScheduledGuardResult evaluate(ScheduledGuardContext context) {
                secondGuardCalls.incrementAndGet();
                return ScheduledGuardResult.decision(ScheduledGuardDecision.proceed());
            }
        };
        ScheduleCapabilityOwner owner = new ScheduleCapabilityOwner(
                "fixture", "fixture-package", 1L);
        ScheduledSourceDescriptor descriptor = new ScheduledSourceDescriptor(
                SOURCE, Set.of(), "fixture.definition", 1,
                new ScheduledSourcePresentation(
                        "fixture", "source.label", "source.summary", "schedule", "neutral"),
                Set.of("fixture"), Set.of(WORK), Set.of(POLICY),
                Set.of(firstGuardId, secondGuardId), null);
        publication.set(ScheduleCapabilityTestFixture.publish(
                registry, ScheduleCapabilityTestFixture.bundle(
                        owner, List.of(descriptor), List.of(source),
                        List.of(workExecutor(context -> ScheduledWorkResult.completed())),
                        List.of(credentialPolicy(new AtomicReference<>())),
                        List.of(firstGuard, secondGuard))));

        assertThatThrownBy(() -> engine(
                storeWithCredential(), registry,
                new ScheduleRunState(), new SyncTaskExecutor()).execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("schedule.cancelled");
                    assertThat(failure.category()).isEqualTo(ScheduledFailure.Category.CANCELLED);
                });
        assertThat(discoveryCalls).hasValue(0);
        assertThat(secondGuardCalls).hasValue(0);
        assertThat(withdrawn.get()).isNotNull();
        assertThat(withdrawn.get().isDrained()).isTrue();
    }

    @Test
    @DisplayName("账号级决定在 publication 撤回替换先赢时不执行旧代挂起写入")
    void retiredAndReplacedPublicationSkipsStaleAccountSuspensionWrite() throws Exception {
        CountDownLatch guardEntered = new CountDownLatch(1);
        CountDownLatch allowGuardDecision = new CountDownLatch(1);
        AtomicInteger suspensionWrites = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(
                1, context -> ScheduledDiscoveryResult.withoutCheckpoint());
        ScheduledWorkExecutor work = workExecutor(
                context -> ScheduledWorkResult.completed());
        ScheduledCredentialPolicy policy = credentialPolicy(new AtomicReference<>());
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() != ScheduledGuardPoint.RUN_START) {
                return ScheduledGuardDecision.proceed();
            }
            guardEntered.countDown();
            try {
                if (!allowGuardDecision.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("fixture guard decision release timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw ScheduledExecutionException.cancelled();
            }
            return new ScheduledGuardDecision(
                    ScheduledGuardDecision.Action.SUSPEND_POLICY_ACCOUNT,
                    "fixture.stale-account-risk",
                    0L);
        });
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication first =
                ScheduleCapabilityTestFixture.publish(
                        registry,
                        bindingBundle(
                                new ScheduleCapabilityOwner(
                                        "fixture", "fixture-package", 1L),
                                source, work, policy, guard));
        ScheduleExecutionEngine engine = engine(
                storeWithCredential(), registry,
                new ScheduleRunState(), new SyncTaskExecutor());
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> execution = caller.submit(() -> catchThrowable(() ->
                    engine.execute(
                            task(),
                            ignored -> {
                            },
                            ignored -> suspensionWrites.incrementAndGet())));

            assertThat(guardEntered.await(5, TimeUnit.SECONDS)).isTrue();
            FakeScheduleCapabilityAccess.Drain drain =
                    ScheduleCapabilityTestFixture.withdraw(registry, first).orElseThrow();
            assertThat(drain.activeLeaseCount()).isEqualTo(1);
            assertThat(drain.isDrained()).isFalse();
            ScheduleCapabilityTestFixture.publish(
                    registry,
                    bindingBundle(
                            new ScheduleCapabilityOwner(
                                    "fixture", "fixture-package", 2L),
                            sourceExecutor(
                                    1, context -> ScheduledDiscoveryResult.withoutCheckpoint()),
                            workExecutor(context -> ScheduledWorkResult.completed()),
                            credentialPolicy(new AtomicReference<>()),
                            guard(context -> ScheduledGuardDecision.proceed())));

            allowGuardDecision.countDown();
            assertThat(execution.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ScheduleSourceUnavailableException.class);
            assertThat(suspensionWrites).hasValue(0);
            assertThat(drain.awaitDrained(
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
        } finally {
            allowGuardDecision.countDown();
            caller.shutdownNow();
            caller.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("来源 ThreadDeath 在 abort 与复合租约释放后原样传播")
    void sourceThreadDeathPropagatesAfterCleanupAndLeaseRelease() throws Exception {
        ThreadDeath fatal = new ThreadDeath();
        AtomicInteger aborts = new AtomicInteger();
        ScheduledSourceExecutor source = sourceWithPlan(
                plan(Set.of(WORK), List.of()),
                context -> {
                    throw fatal;
                });
        ScheduledWorkExecutor work = new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return WORK;
            }

            @Override
            public ScheduledWorkResult execute(
                    ScheduledWork value,
                    ScheduledWorkContext context) {
                return ScheduledWorkResult.completed();
            }

            @Override
            public void abortRun(ScheduledTaskDefinition task) {
                aborts.incrementAndGet();
            }
        };
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication = publishExecutionFixture(
                registry, source, List.of(work), List.of(), Set.of(WORK), Set.of());

        ThreadDeath observed;
        try {
            engine(storeWithCredential(), registry,
                    new ScheduleRunState(), new SyncTaskExecutor()).execute(task());
            throw new AssertionError("expected source ThreadDeath");
        } catch (ThreadDeath failure) {
            observed = failure;
        }

        assertThat(observed).isSameAs(fatal);
        assertThat(aborts).hasValue(1);
        FakeScheduleCapabilityAccess.Drain drain =
                ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("作品 worker 的 VMError 与 ThreadDeath 在 abort 和租约清理后原样传播")
    void workerFatalsPropagateAfterAbortAndLeaseRelease() throws Exception {
        List<Error> fatals = List.of(
                new TestVirtualMachineError("fixture worker vm error"),
                new ThreadDeath());
        int sequence = 0;
        for (Error fatal : fatals) {
            String workId = "fatal-worker-" + sequence++;
            AtomicInteger aborts = new AtomicInteger();
            ScheduledSourceExecutor source = sourceWithPlan(
                    plan(Set.of(WORK), List.of()),
                    context -> {
                        context.workSink().submit(work(workId));
                        return ScheduledDiscoveryResult.withoutCheckpoint();
                    });
            ScheduledWorkExecutor work = new ScheduledWorkExecutor() {
                @Override
                public String workType() {
                    return WORK;
                }

                @Override
                public ScheduledWorkResult execute(
                        ScheduledWork value,
                        ScheduledWorkContext context) {
                    throw fatal;
                }

                @Override
                public void abortRun(ScheduledTaskDefinition task) {
                    aborts.incrementAndGet();
                }
            };
            FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
            FakeScheduleCapabilityAccess.Publication publication = publishExecutionFixture(
                    registry, source, List.of(work), List.of(), Set.of(WORK), Set.of());
            ScheduledTaskStore store = storeWithCredential();

            Throwable observed = catchThrowable(() -> engine(
                    store, registry,
                    new ScheduleRunState(), new SyncTaskExecutor()).execute(task()));

            assertThat(observed).isSameAs(fatal);
            assertThat(aborts).hasValue(1);
            verify(store).upsertPendingWork(any());
            FakeScheduleCapabilityAccess.Drain drain =
                    ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow();
            assertThat(drain.isDrained()).isTrue();
        }
    }

    @Test
    @DisplayName("计划失败投影抛出 ThreadDeath 时先释放 planning 租约再原样传播")
    void fatalPlanFailureProjectionReleasesPlanningLease() throws Exception {
        ThreadDeath fatal = new ThreadDeath();
        ScheduledSourceExecutor source = new ScheduledSourceExecutor() {
            @Override
            public String sourceType() {
                return SOURCE;
            }

            @Override
            public ScheduledExecutionPlan plan(ScheduledTaskDefinition task)
                    throws ScheduledExecutionException {
                throw new ScheduledExecutionException(
                        ScheduledFailure.Category.INTERNAL,
                        "fixture.plan-failure") {
                    @Override
                    public String code() {
                        throw fatal;
                    }
                };
            }

            @Override
            public ScheduledDiscoveryResult discover(ScheduledSourceContext context) {
                return ScheduledDiscoveryResult.withoutCheckpoint();
            }
        };
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication = publishExecutionFixture(
                registry,
                source,
                List.of(workExecutor(context -> ScheduledWorkResult.completed())),
                List.of(),
                Set.of(WORK),
                Set.of());

        ThreadDeath observed;
        try {
            engine(storeWithCredential(), registry,
                    new ScheduleRunState(), new SyncTaskExecutor()).execute(task());
            throw new AssertionError("expected plan failure projection ThreadDeath");
        } catch (ThreadDeath failure) {
            observed = failure;
        }

        assertThat(observed).isSameAs(fatal);
        FakeScheduleCapabilityAccess.Drain drain =
                ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("多个 abort fatal 完成全部清理后以 suppressed 保留并传播首错")
    void multipleAbortFatalsAreSuppressedAfterAllCleanup() throws Exception {
        String secondWorkType = "fixture-work-2";
        TestVirtualMachineError firstFatal = new TestVirtualMachineError("fixture abort fatal one");
        TestVirtualMachineError secondFatal = new TestVirtualMachineError("fixture abort fatal two");
        List<String> aborts = new ArrayList<>();
        ScheduledSourceExecutor source = sourceWithPlan(
                plan(Set.of(WORK, secondWorkType), List.of()),
                context -> {
                    throw new ScheduledExecutionException(
                            ScheduledFailure.Category.RETRYABLE_NETWORK,
                            "fixture.primary-source-failure");
                });
        ScheduledWorkExecutor firstWork = abortingExecutor(WORK, firstFatal, aborts);
        ScheduledWorkExecutor secondWork = abortingExecutor(secondWorkType, secondFatal, aborts);
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication = publishExecutionFixture(
                registry, source, List.of(firstWork, secondWork), List.of(),
                Set.of(WORK, secondWorkType), Set.of());

        VirtualMachineError observed;
        try {
            engine(storeWithCredential(), registry,
                    new ScheduleRunState(), new SyncTaskExecutor()).execute(task());
            throw new AssertionError("expected abort fatal error");
        } catch (VirtualMachineError failure) {
            observed = failure;
        }

        VirtualMachineError other = observed == firstFatal ? secondFatal : firstFatal;
        assertThat(observed).isIn(firstFatal, secondFatal);
        assertThat(observed.getSuppressed()).contains(other);
        assertThat(aborts).containsExactlyInAnyOrder(WORK, secondWorkType);
        FakeScheduleCapabilityAccess.Drain drain =
                ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("多个 finalizer fatal 完成全部 finalizer 与 abort 后以 suppressed 保留")
    void multipleFinalizerFatalsAreSuppressedAfterAllCleanup() throws Exception {
        String secondWorkType = "fixture-work-2";
        TestVirtualMachineError firstFatal = new TestVirtualMachineError("fixture finalizer fatal one");
        TestVirtualMachineError secondFatal = new TestVirtualMachineError("fixture finalizer fatal two");
        List<String> events = new ArrayList<>();
        ScheduledSourceExecutor source = sourceWithPlan(
                plan(Set.of(WORK, secondWorkType), List.of()),
                context -> ScheduledDiscoveryResult.withoutCheckpoint());
        ScheduledWorkExecutor firstWork = fatalFinalizingExecutor(WORK, firstFatal, events);
        ScheduledWorkExecutor secondWork = fatalFinalizingExecutor(secondWorkType, secondFatal, events);
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication = publishExecutionFixture(
                registry, source, List.of(firstWork, secondWork), List.of(),
                Set.of(WORK, secondWorkType), Set.of());

        VirtualMachineError observed;
        try {
            engine(storeWithCredential(), registry,
                    new ScheduleRunState(), new SyncTaskExecutor()).execute(task());
            throw new AssertionError("expected finalizer fatal error");
        } catch (VirtualMachineError failure) {
            observed = failure;
        }

        VirtualMachineError other = observed == firstFatal ? secondFatal : firstFatal;
        assertThat(observed).isIn(firstFatal, secondFatal);
        assertThat(observed.getSuppressed()).contains(other);
        assertThat(events).containsExactlyInAnyOrder(
                "finish-" + WORK,
                "finish-" + secondWorkType,
                "abort-" + WORK,
                "abort-" + secondWorkType);
        FakeScheduleCapabilityAccess.Drain drain =
                ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow();
        assertThat(drain.isDrained()).isTrue();
    }

}
