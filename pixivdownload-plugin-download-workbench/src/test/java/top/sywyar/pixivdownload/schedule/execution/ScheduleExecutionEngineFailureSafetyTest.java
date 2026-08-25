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

@DisplayName("计划执行引擎失败净化与收尾")
class ScheduleExecutionEngineFailureSafetyTest extends ScheduleExecutionEngineTestSupport {

    @Test
    @DisplayName("插件声明失败码含凭证形态时在 Guard、日志与持久化边界前归一")
    void unsafePluginFailureCodeIsNormalized() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        AtomicReference<String> observedFailureCode = new AtomicReference<>();
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.RETRYABLE_NETWORK,
                    "Cookie: PHPSESSID=secret");
        });
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.RUN_FAILURE) {
                observedFailureCode.set(context.failure().code());
            }
            return ScheduledGuardDecision.proceed();
        });

        assertThatThrownBy(() -> engine(
                store, source,
                workExecutor(context -> ScheduledWorkResult.completed()),
                credentialPolicy(new AtomicReference<>()), guard).execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.execution.invalid-failure-code"));
        assertThat(observedFailureCode).hasValue("schedule.execution.invalid-failure-code");
    }

    @Test
    @DisplayName("来源异常中的原始凭证回显在失败 Guard 前归一为固定机器码")
    void sourceFailureExactCredentialEchoIsNormalized() throws Exception {
        AtomicReference<String> observedFailureCode = new AtomicReference<>();
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.RETRYABLE_NETWORK,
                    "fixture-secret");
        });
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.RUN_FAILURE) {
                observedFailureCode.set(context.failure().code());
            }
            return ScheduledGuardDecision.proceed();
        });

        assertThatThrownBy(() -> engine(
                storeWithCredential(), source,
                workExecutor(context -> ScheduledWorkResult.completed()),
                credentialPolicy(new AtomicReference<>()), guard).execute(task()))
                .isInstanceOfSatisfying(
                        ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.execution.invalid-failure-code"));
        assertThat(observedFailureCode)
                .hasValue("schedule.execution.invalid-failure-code");
    }

    @Test
    @DisplayName("作品收尾异常中的原始凭证回显统一转为固定机器码")
    void workFinalizerExactCredentialEchoIsNormalized() throws Exception {
        AtomicReference<String> observedFailureCode = new AtomicReference<>();
        ScheduledWorkExecutor executor = new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return WORK;
            }

            @Override
            public ScheduledWorkResult execute(
                    ScheduledWork work,
                    ScheduledWorkContext context) {
                return ScheduledWorkResult.completed();
            }

            @Override
            public void finishRun(ScheduledWorkRunContext context)
                    throws ScheduledExecutionException {
                throw new ScheduledExecutionException(
                        ScheduledFailure.Category.INTERNAL,
                        "fixture-secret");
            }
        };
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.RUN_FAILURE) {
                observedFailureCode.set(context.failure().code());
            }
            return ScheduledGuardDecision.proceed();
        });

        assertThatThrownBy(() -> engine(
                storeWithCredential(),
                sourceExecutor(
                        1,
                        context -> ScheduledDiscoveryResult.withoutCheckpoint()),
                executor,
                credentialPolicy(new AtomicReference<>()),
                guard).execute(task()))
                .isInstanceOfSatisfying(
                        ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.work.finalizer-failed"));
        assertThat(observedFailureCode).hasValue("schedule.work.finalizer-failed");
    }

    @Test
    @DisplayName("Guard 决定与证据中的原始凭证回显统一转为固定失败")
    void guardResultRejectsExactCredentialEcho() throws Exception {
        List<ScheduledGuardResult> results = List.of(
                ScheduledGuardResult.decision(new ScheduledGuardDecision(
                        ScheduledGuardDecision.Action.FAIL,
                        "fixture-secret",
                        0L)),
                new ScheduledGuardResult(
                        new ScheduledGuardDecision(
                                ScheduledGuardDecision.Action.SUSPEND_POLICY_TASK,
                                "fixture.risk",
                                0L),
                        new ScheduledGuardEvidence(
                                Map.of("excerpt", "fixture-secret"))));

        for (ScheduledGuardResult result : results) {
            ScheduledExecutionGuard guard = new ScheduledExecutionGuard() {
                @Override
                public String guardId() {
                    return GUARD;
                }

                @Override
                public ScheduledGuardResult evaluate(ScheduledGuardContext context) {
                    return result;
                }
            };

            assertThatThrownBy(() -> engine(
                    storeWithCredential(),
                    sourceExecutor(
                            1,
                            context -> ScheduledDiscoveryResult.withoutCheckpoint()),
                    workExecutor(context -> ScheduledWorkResult.completed()),
                    credentialPolicy(new AtomicReference<>()),
                    guard).execute(task()))
                    .isInstanceOfSatisfying(
                            ScheduledExecutionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("schedule.guard.invalid-result"));
        }
    }

    @Test
    @DisplayName("通用凭证熔断穿过引擎异常边界后仍保留计数与末次安全错误码")
    void credentialCircuitControlDataSurvivesEngineBoundary() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        when(store.upsertPendingWork(any())).thenReturn(1);
        ScheduledSourceExecutor source = sourceExecutor(5, context -> {
            for (int index = 0; index < 5; index++) {
                context.workSink().submit(work("credential-failure-" + index));
            }
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledWorkExecutor executor = workExecutor(context -> {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.CREDENTIAL_INVALID,
                    "fixture.credential-expired");
        });

        assertThatThrownBy(() -> engine(
                store, source, executor,
                credentialPolicy(new AtomicReference<>()),
                guard(context -> ScheduledGuardDecision.proceed())).execute(task()))
                .isInstanceOfSatisfying(
                        ScheduleCredentialCircuitOpenException.class,
                        failure -> {
                            assertThat(failure.consecutiveFailures()).isEqualTo(5);
                            assertThat(failure.lastFailureCode())
                                    .isEqualTo("fixture.credential-expired");
                        });
    }

    @Test
    @DisplayName("多个 finalizer 与失败 Guard 各自调用一次且首错不阻断后续能力")
    void allFinalizersAndFailureGuardsRunOnce() throws Exception {
        String secondWorkType = "fixture-work-2";
        String secondGuardId = "fixture-guard-2";
        List<String> events = new ArrayList<>();
        ScheduledSourceExecutor source = new ScheduledSourceExecutor() {
            @Override
            public String sourceType() {
                return SOURCE;
            }

            @Override
            public ScheduledExecutionPlan plan(ScheduledTaskDefinition task) {
                return new ScheduledExecutionPlan(
                        Set.of(WORK, secondWorkType), POLICY,
                        ScheduledCredentialRequirement.REQUIRED, false,
                        List.of(
                                new ScheduledGuardBinding(
                                        GUARD, Set.of(ScheduledGuardPoint.RUN_FAILURE), 0),
                                new ScheduledGuardBinding(
                                        secondGuardId, Set.of(ScheduledGuardPoint.RUN_FAILURE), 0)),
                        "fixture.checkpoint", 1, 1, 0L,
                        ScheduledNetworkRoute.inherit());
            }

            @Override
            public ScheduledDiscoveryResult discover(ScheduledSourceContext context) {
                return ScheduledDiscoveryResult.withoutCheckpoint();
            }
        };
        ScheduledWorkExecutor firstWork = finalizingExecutor(WORK, events, true);
        ScheduledWorkExecutor secondWork = finalizingExecutor(secondWorkType, events, false);
        ScheduledExecutionGuard firstGuard = new ScheduledExecutionGuard() {
            @Override
            public String guardId() {
                return GUARD;
            }

            @Override
            public ScheduledGuardResult evaluate(ScheduledGuardContext context)
                    throws ScheduledExecutionException {
                events.add("guard-1");
                throw new ScheduledExecutionException(
                        ScheduledFailure.Category.INTERNAL, "fixture.guard-one-failed");
            }
        };
        ScheduledExecutionGuard secondGuard = new ScheduledExecutionGuard() {
            @Override
            public String guardId() {
                return secondGuardId;
            }

            @Override
            public ScheduledGuardResult evaluate(ScheduledGuardContext context) {
                events.add("guard-2");
                return ScheduledGuardResult.decision(ScheduledGuardDecision.proceed());
            }
        };
        ScheduledSourceDescriptor descriptor = new ScheduledSourceDescriptor(
                SOURCE, Set.of(), "fixture.definition", 1,
                new ScheduledSourcePresentation(
                        "fixture", "source.label", "source.summary", "schedule", "neutral"),
                Set.of("fixture"), Set.of(WORK, secondWorkType),
                Set.of(POLICY), Set.of(GUARD, secondGuardId), null);
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        publish(registry, ScheduleCapabilityTestFixture.bundle(
                new ScheduleCapabilityOwner("fixture", "fixture-package", 1L),
                List.of(descriptor), List.of(source),
                List.of(firstWork, secondWork),
                List.of(credentialPolicy(new AtomicReference<>())),
                List.of(firstGuard, secondGuard)));

        assertThatThrownBy(() -> engine(
                storeWithCredential(), registry,
                new ScheduleRunState(), new SyncTaskExecutor()).execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("fixture.finalizer-failed"));
        assertThat(events).containsExactlyInAnyOrder(
                "finalizer-" + WORK,
                "finalizer-" + secondWorkType,
                "abort-" + WORK,
                "abort-" + secondWorkType,
                "guard-1",
                "guard-2");
    }

    @Test
    @DisplayName("失败 Guard 全部执行后采用首个非继续决定")
    void firstFailureGuardDecisionWinsAfterCleanup() throws Exception {
        String secondGuardId = "fixture-guard-second";
        List<String> events = new ArrayList<>();
        ScheduledSourceExecutor source = sourceWithPlan(
                plan(Set.of(WORK), List.of(
                        new ScheduledGuardBinding(
                                GUARD, Set.of(ScheduledGuardPoint.RUN_FAILURE), 0),
                        new ScheduledGuardBinding(
                                secondGuardId, Set.of(ScheduledGuardPoint.RUN_FAILURE), 0))),
                context -> {
                    throw new ScheduledExecutionException(
                            ScheduledFailure.Category.RETRYABLE_NETWORK,
                            "fixture.primary-failure");
                });
        ScheduledExecutionGuard firstGuard = guard(GUARD, context -> {
            events.add("guard-1");
            return new ScheduledGuardDecision(
                    ScheduledGuardDecision.Action.SUSPEND_POLICY_ACCOUNT,
                    "fixture.first-policy",
                    0L);
        });
        ScheduledExecutionGuard secondGuard = guard(secondGuardId, context -> {
            events.add("guard-2");
            return new ScheduledGuardDecision(
                    ScheduledGuardDecision.Action.FAIL,
                    "fixture.second-fail",
                    0L);
        });
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        publishExecutionFixture(
                registry,
                source,
                List.of(finalizingExecutor(WORK, events, false)),
                List.of(firstGuard, secondGuard),
                Set.of(WORK),
                Set.of(GUARD, secondGuardId));

        assertThatThrownBy(() -> engine(
                storeWithCredential(), registry,
                new ScheduleRunState(), new SyncTaskExecutor()).execute(task()))
                .isInstanceOfSatisfying(ScheduleExecutionControlException.class, control -> {
                    assertThat(control.action()).isEqualTo(
                            ScheduledGuardDecision.Action.SUSPEND_POLICY_ACCOUNT);
                    assertThat(control.reasonCode()).isEqualTo("fixture.first-policy");
                    assertThat(control.retryAfterMillis()).isZero();
                    assertThat(control.evidence().attributes()).isEmpty();
                });
        assertThat(events).containsExactly(
                "abort-" + WORK,
                "guard-1",
                "guard-2");
    }

    @Test
    @DisplayName("失败 Guard 的非致命 Error 不覆盖主失败且不阻断后续 Guard")
    void nonFatalFailureGuardErrorPreservesPrimaryAndContinues() throws Exception {
        String secondGuardId = "fixture-guard-second";
        List<String> events = new ArrayList<>();
        ScheduledSourceExecutor source = sourceWithPlan(
                plan(Set.of(WORK), List.of(
                        new ScheduledGuardBinding(
                                GUARD, Set.of(ScheduledGuardPoint.RUN_FAILURE), 0),
                        new ScheduledGuardBinding(
                                secondGuardId, Set.of(ScheduledGuardPoint.RUN_FAILURE), 0))),
                context -> {
                    throw new ScheduledExecutionException(
                            ScheduledFailure.Category.RETRYABLE_NETWORK,
                            "fixture.primary-source-failure");
                });
        ScheduledExecutionGuard firstGuard = new ScheduledExecutionGuard() {
            @Override
            public String guardId() {
                return GUARD;
            }

            @Override
            public ScheduledGuardResult evaluate(ScheduledGuardContext context) {
                events.add("guard-1");
                throw new AssertionError("fixture non-fatal guard failure");
            }
        };
        ScheduledExecutionGuard secondGuard = new ScheduledExecutionGuard() {
            @Override
            public String guardId() {
                return secondGuardId;
            }

            @Override
            public ScheduledGuardResult evaluate(ScheduledGuardContext context) {
                events.add("guard-2");
                return ScheduledGuardResult.decision(ScheduledGuardDecision.proceed());
            }
        };
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        publishExecutionFixture(
                registry,
                source,
                List.of(workExecutor(context -> ScheduledWorkResult.completed())),
                List.of(firstGuard, secondGuard),
                Set.of(WORK),
                Set.of(GUARD, secondGuardId));

        assertThatThrownBy(() -> engine(
                storeWithCredential(), registry,
                new ScheduleRunState(), new SyncTaskExecutor()).execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("fixture.primary-source-failure"));
        assertThat(events).containsExactly("guard-1", "guard-2");
    }

    @Test
    @DisplayName("失败 Guard 的 fatal 延后到后续 Guard 与租约清理完成后原样传播")
    void fatalFailureGuardContinuesThenPropagatesAfterLeaseRelease() throws Exception {
        String secondGuardId = "fixture-guard-second";
        TestVirtualMachineError fatal = new TestVirtualMachineError("fixture fatal guard failure");
        AtomicInteger secondGuardCalls = new AtomicInteger();
        ScheduledSourceExecutor source = sourceWithPlan(
                plan(Set.of(WORK), List.of(
                        new ScheduledGuardBinding(
                                GUARD, Set.of(ScheduledGuardPoint.RUN_FAILURE), 0),
                        new ScheduledGuardBinding(
                                secondGuardId, Set.of(ScheduledGuardPoint.RUN_FAILURE), 0))),
                context -> {
                    throw new ScheduledExecutionException(
                            ScheduledFailure.Category.RETRYABLE_NETWORK,
                            "fixture.primary-source-failure");
                });
        ScheduledExecutionGuard firstGuard = new ScheduledExecutionGuard() {
            @Override
            public String guardId() {
                return GUARD;
            }

            @Override
            public ScheduledGuardResult evaluate(ScheduledGuardContext context) {
                throw fatal;
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
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication = publishExecutionFixture(
                registry,
                source,
                List.of(workExecutor(context -> ScheduledWorkResult.completed())),
                List.of(firstGuard, secondGuard),
                Set.of(WORK),
                Set.of(GUARD, secondGuardId));

        VirtualMachineError observed;
        try {
            engine(storeWithCredential(), registry,
                    new ScheduleRunState(), new SyncTaskExecutor()).execute(task());
            throw new AssertionError("expected failure Guard fatal error");
        } catch (VirtualMachineError failure) {
            observed = failure;
        }

        assertThat(observed).isSameAs(fatal);
        assertThat(secondGuardCalls).hasValue(1);
        FakeScheduleCapabilityAccess.Drain drain =
                ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("失败 Guard 已有决定时后续 fatal 仍在租约释放后优先传播")
    void fatalAfterFailureGuardDecisionWinsAfterLeaseRelease() throws Exception {
        String secondGuardId = "fixture-guard-second";
        TestVirtualMachineError fatal = new TestVirtualMachineError("fixture fatal after decision");
        AtomicInteger firstGuardCalls = new AtomicInteger();
        ScheduledSourceExecutor source = sourceWithPlan(
                plan(Set.of(WORK), List.of(
                        new ScheduledGuardBinding(
                                GUARD, Set.of(ScheduledGuardPoint.RUN_FAILURE), 0),
                        new ScheduledGuardBinding(
                                secondGuardId, Set.of(ScheduledGuardPoint.RUN_FAILURE), 0))),
                context -> {
                    throw new ScheduledExecutionException(
                            ScheduledFailure.Category.RETRYABLE_NETWORK,
                            "fixture.primary-source-failure");
                });
        ScheduledExecutionGuard firstGuard = guard(GUARD, context -> {
            firstGuardCalls.incrementAndGet();
            return new ScheduledGuardDecision(
                    ScheduledGuardDecision.Action.SUSPEND_POLICY_TASK,
                    "fixture.policy-suspend",
                    0L);
        });
        ScheduledExecutionGuard secondGuard = guard(secondGuardId, context -> {
            throw fatal;
        });
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication = publishExecutionFixture(
                registry,
                source,
                List.of(workExecutor(context -> ScheduledWorkResult.completed())),
                List.of(firstGuard, secondGuard),
                Set.of(WORK),
                Set.of(GUARD, secondGuardId));

        VirtualMachineError observed;
        try {
            engine(storeWithCredential(), registry,
                    new ScheduleRunState(), new SyncTaskExecutor()).execute(task());
            throw new AssertionError("expected failure Guard fatal error");
        } catch (VirtualMachineError failure) {
            observed = failure;
        }

        assertThat(observed).isSameAs(fatal);
        assertThat(firstGuardCalls).hasValue(1);
        FakeScheduleCapabilityAccess.Drain drain =
                ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow();
        assertThat(drain.isDrained()).isTrue();
    }

}
