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

@DisplayName("计划执行引擎 Guard 与凭证失败")
class ScheduleExecutionEngineGuardTest extends ScheduleExecutionEngineTestSupport {

    @Test
    @DisplayName("1001 件作品严格在 500 与 1000 排空后调用 Guard 并共享同一 route")
    void guardsAtExactBatchBarriersAndSharesRouteIdentity() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        AtomicReference<ScheduledNetworkRoute> routeIdentity = new AtomicReference<>();
        List<String> events = new ArrayList<>();
        AtomicInteger executed = new AtomicInteger();
        AtomicBoolean finalizerCalled = new AtomicBoolean();

        ScheduledSourceExecutor source = sourceExecutor(1001, context -> {
            assertSameRoute(routeIdentity, context.route());
            for (int i = 1; i <= 1001; i++) {
                context.workSink().submit(work(Integer.toString(i)));
            }
            return ScheduledDiscoveryResult.withCheckpoint(
                    new ScheduledCheckpoint("fixture.checkpoint", 1, "{\"cursor\":\"done\"}"));
        });
        ScheduledWorkExecutor work = new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return WORK;
            }

            @Override
            public ScheduledWorkResult execute(ScheduledWork value, ScheduledWorkContext context) {
                assertSameRoute(routeIdentity, context.route());
                executed.incrementAndGet();
                return ScheduledWorkResult.completed();
            }

            @Override
            public void finishRun(ScheduledWorkRunContext context) {
                assertSameRoute(routeIdentity, context.route());
                assertThat(context.statistics().attemptedWorkCount()).isEqualTo(1001);
                assertThat(context.statistics().completedWorkCount()).isEqualTo(1001);
                events.add("finalizer");
                finalizerCalled.set(true);
            }
        };
        ScheduledCredentialPolicy policy = credentialPolicy(routeIdentity);
        ScheduledExecutionGuard guard = guard(context -> {
            assertSameRoute(routeIdentity, context.route());
            events.add(switch (context.point()) {
                case RUN_START -> "start";
                case WORK_BATCH -> "batch-" + context.attemptedWorkCount();
                case RUN_END -> "end";
                case RUN_FAILURE -> "failure";
            });
            if (context.point() == ScheduledGuardPoint.WORK_BATCH) {
                assertThat(executed).hasValue((int) context.attemptedWorkCount());
            }
            if (context.point() == ScheduledGuardPoint.RUN_END) {
                assertThat(finalizerCalled).isTrue();
            }
            return ScheduledGuardDecision.proceed();
        });

        ScheduleExecutionEngine engine = engine(store, source, work, policy, guard);
        ScheduleExecutionResult result = engine.execute(task());

        assertThat(result.completedWorkCount()).isEqualTo(1001);
        assertThat(result.candidateCheckpoint().payloadJson()).contains("done");
        assertThat(events).containsExactly("start", "batch-500", "batch-1000", "finalizer", "end");
        assertThat(routeIdentity.get()).isNotNull();
        verify(store).findCredentialSecret(1L, "fixture", POLICY);
    }

    @Test
    @DisplayName("批次 Guard 拒绝后不会接受第 501 件且不递归调用失败 Guard")
    void batchRejectionPreventsNextWorkWithoutFailureGuard() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger failureGuards = new AtomicInteger();
        AtomicInteger finalizers = new AtomicInteger();
        AtomicInteger aborts = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(501, context -> {
            for (int i = 1; i <= 501; i++) {
                context.workSink().submit(work(Integer.toString(i)));
            }
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledWorkExecutor executor = new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return WORK;
            }

            @Override
            public ScheduledWorkResult execute(
                    ScheduledWork work,
                    ScheduledWorkContext context) {
                executed.incrementAndGet();
                return ScheduledWorkResult.completed();
            }

            @Override
            public void finishRun(ScheduledWorkRunContext context) {
                finalizers.incrementAndGet();
            }

            @Override
            public void abortRun(ScheduledTaskDefinition task) {
                aborts.incrementAndGet();
            }
        };
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.RUN_FAILURE) {
                failureGuards.incrementAndGet();
            }
            if (context.point() == ScheduledGuardPoint.WORK_BATCH) {
                return new ScheduledGuardDecision(
                        ScheduledGuardDecision.Action.FAIL, "fixture.stop", 0L);
            }
            return ScheduledGuardDecision.proceed();
        });

        ScheduleExecutionEngine engine = engine(
                store, source, executor, credentialPolicy(new AtomicReference<>()), guard);

        assertThatThrownBy(() -> engine.execute(task()))
                .isInstanceOf(ScheduleExecutionControlException.class)
                .hasMessage("fixture.stop");
        assertThat(executed).hasValue(500);
        assertThat(failureGuards).hasValue(0);
        assertThat(finalizers).hasValue(0);
        assertThat(aborts).hasValue(1);
    }

    @Test
    @DisplayName("来源失败只调用一次失败 Guard 且 Guard 异常不覆盖原始分类")
    void failureGuardRunsOnceWithoutReplacingPrimaryFailure() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        AtomicInteger failureGuards = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.RETRYABLE_NETWORK,
                    "fixture.primary-failure");
        });
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.RUN_FAILURE) {
                failureGuards.incrementAndGet();
                throw new ScheduledExecutionException(
                        ScheduledFailure.Category.INTERNAL,
                        "fixture.failure-guard-broke");
            }
            return ScheduledGuardDecision.proceed();
        });

        ScheduleExecutionEngine engine = engine(
                store, source,
                workExecutor(context -> ScheduledWorkResult.completed()),
                credentialPolicy(new AtomicReference<>()), guard);

        assertThatThrownBy(() -> engine.execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code()).isEqualTo("fixture.primary-failure"));
        assertThat(failureGuards).hasValue(1);
    }

    @Test
    @DisplayName("凭证探活安全失败以零尝试次数调用一次失败 Guard")
    void credentialProbeChallengeInvokesFailureGuardOnce() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        AtomicInteger discoveries = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger failureGuards = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            discoveries.incrementAndGet();
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledWorkExecutor executor = workExecutor(context -> {
            executions.incrementAndGet();
            return ScheduledWorkResult.completed();
        });
        ScheduledCredentialPolicy policy = new ScheduledCredentialPolicy() {
            @Override
            public String policyId() {
                return POLICY;
            }

            @Override
            public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context)
                    throws ScheduledExecutionException {
                throw new ScheduledExecutionException(
                        ScheduledFailure.Category.CHALLENGE,
                        "fixture.probe-challenge",
                        1_234L);
            }
        };
        ScheduledExecutionGuard guard = guard(context -> {
            failureGuards.incrementAndGet();
            assertThat(context.point()).isEqualTo(ScheduledGuardPoint.RUN_FAILURE);
            assertThat(context.attemptedWorkCount()).isZero();
            assertThat(context.failure().category()).isEqualTo(ScheduledFailure.Category.CHALLENGE);
            assertThat(context.failure().code()).isEqualTo("fixture.probe-challenge");
            assertThat(context.failure().retryAfterMillis()).isEqualTo(1_234L);
            return new ScheduledGuardDecision(
                    ScheduledGuardDecision.Action.SUSPEND_POLICY_ACCOUNT,
                    "fixture.probe-policy-suspend",
                    0L);
        });

        assertThatThrownBy(() -> engine(store, source, executor, policy, guard).execute(task()))
                .isInstanceOfSatisfying(ScheduleExecutionControlException.class, control -> {
                    assertThat(control.action()).isEqualTo(
                            ScheduledGuardDecision.Action.SUSPEND_POLICY_ACCOUNT);
                    assertThat(control.reasonCode()).isEqualTo("fixture.probe-policy-suspend");
                });
        assertThat(failureGuards).hasValue(1);
        assertThat(discoveries).hasValue(0);
        assertThat(executions).hasValue(0);
    }

    @Test
    @DisplayName("凭证探活失败的账号级决定在三参数 publication barrier 内仅发布一次")
    void credentialProbeFailurePublishesAccountDecisionExactlyOnce() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        AtomicInteger discoveries = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger failureGuards = new AtomicInteger();
        AtomicInteger publisherCalls = new AtomicInteger();
        List<String> events = new ArrayList<>();
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            discoveries.incrementAndGet();
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledWorkExecutor executor = workExecutor(context -> {
            executions.incrementAndGet();
            return ScheduledWorkResult.completed();
        });
        ScheduledCredentialPolicy policy = new ScheduledCredentialPolicy() {
            @Override
            public String policyId() {
                return POLICY;
            }

            @Override
            public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context)
                    throws ScheduledExecutionException {
                throw new ScheduledExecutionException(
                        ScheduledFailure.Category.CHALLENGE,
                        "fixture.probe-challenge",
                        1_234L);
            }
        };
        ScheduledExecutionGuard guard = guard(context -> {
            failureGuards.incrementAndGet();
            events.add("failure-guard");
            assertThat(context.point()).isEqualTo(ScheduledGuardPoint.RUN_FAILURE);
            assertThat(context.attemptedWorkCount()).isZero();
            return new ScheduledGuardDecision(
                    ScheduledGuardDecision.Action.SUSPEND_POLICY_ACCOUNT,
                    "fixture.probe-policy-suspend",
                    0L);
        });
        ScheduleExecutionEngine engine = engine(store, source, executor, policy, guard);

        assertThatThrownBy(() -> engine.execute(
                task(),
                ignored -> {
                },
                decision -> {
                    assertThat(failureGuards).hasValue(1);
                    publisherCalls.incrementAndGet();
                    events.add("publisher");
                    assertThat(decision.action()).isEqualTo(
                            ScheduledGuardDecision.Action.SUSPEND_POLICY_ACCOUNT);
                    assertThat(decision.reasonCode()).isEqualTo(
                            "fixture.probe-policy-suspend");
                }))
                .isInstanceOfSatisfying(ScheduleExecutionControlException.class, control -> {
                    assertThat(control.action()).isEqualTo(
                            ScheduledGuardDecision.Action.SUSPEND_POLICY_ACCOUNT);
                    assertThat(control.reasonCode()).isEqualTo(
                            "fixture.probe-policy-suspend");
                });
        assertThat(failureGuards).hasValue(1);
        assertThat(publisherCalls).hasValue(1);
        assertThat(events).containsExactly("failure-guard", "publisher");
        assertThat(discoveries).hasValue(0);
        assertThat(executions).hasValue(0);
    }

    @Test
    @DisplayName("正式凭证探活拒绝账号与机器码中的原始凭证回显")
    void credentialProbeRejectsExactCredentialEcho() throws Exception {
        List<ProbeEchoCase> cases = List.of(
                new ProbeEchoCase(
                        "accountKey",
                        new ScheduledCredentialProbeResult(
                                ScheduledCredentialProbeResult.Status.VALID,
                                "fixture-secret",
                                "credential.valid",
                                0L)),
                new ProbeEchoCase(
                        "code",
                        new ScheduledCredentialProbeResult(
                                ScheduledCredentialProbeResult.Status.VALID,
                                "account-1",
                                "fixture-secret",
                                0L)));

        for (ProbeEchoCase echoCase : cases) {
            AtomicBoolean discovered = new AtomicBoolean();
            ScheduledCredentialPolicy policy = new ScheduledCredentialPolicy() {
                @Override
                public String policyId() {
                    return POLICY;
                }

                @Override
                public ScheduledCredentialProbeResult probe(
                        ScheduledCredentialContext context) {
                    return echoCase.result();
                }
            };

            assertThatThrownBy(() -> engine(
                    storeWithCredential(),
                    sourceExecutor(1, context -> {
                        discovered.set(true);
                        return ScheduledDiscoveryResult.withoutCheckpoint();
                    }),
                    workExecutor(context -> ScheduledWorkResult.completed()),
                    policy,
                    guard(context -> ScheduledGuardDecision.proceed())).execute(task()))
                    .as(echoCase.field())
                    .isInstanceOfSatisfying(
                            ScheduledExecutionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("schedule.credential.invalid-result"));
            assertThat(discovered).isFalse();
        }
    }

    @Test
    @DisplayName("凭证探活控制结果不会递归调用失败 Guard")
    void credentialProbeControlResultSkipsFailureGuard() throws Exception {
        for (ScheduledCredentialProbeResult probeResult : List.of(
                ScheduledCredentialProbeResult.invalid("fixture.probe-invalid"),
                ScheduledCredentialProbeResult.retryLater("fixture.probe-retry", 1_000L))) {
            AtomicInteger failureGuards = new AtomicInteger();
            ScheduledCredentialPolicy policy = new ScheduledCredentialPolicy() {
                @Override
                public String policyId() {
                    return POLICY;
                }

                @Override
                public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context) {
                    return probeResult;
                }
            };
            ScheduledExecutionGuard guard = guard(context -> {
                if (context.point() == ScheduledGuardPoint.RUN_FAILURE) {
                    failureGuards.incrementAndGet();
                }
                return ScheduledGuardDecision.proceed();
            });

            assertThatThrownBy(() -> engine(
                    storeWithCredential(),
                    sourceExecutor(1, context -> ScheduledDiscoveryResult.withoutCheckpoint()),
                    workExecutor(context -> ScheduledWorkResult.completed()),
                    policy,
                    guard).execute(task()))
                    .isInstanceOf(ScheduleExecutionControlException.class);
            assertThat(failureGuards).hasValue(0);
        }
    }

    @Test
    @DisplayName("凭证探活取消不会调用失败 Guard")
    void credentialProbeCancellationSkipsFailureGuard() throws Exception {
        AtomicInteger failureGuards = new AtomicInteger();
        ScheduledCredentialPolicy policy = new ScheduledCredentialPolicy() {
            @Override
            public String policyId() {
                return POLICY;
            }

            @Override
            public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context)
                    throws ScheduledExecutionException {
                throw ScheduledExecutionException.cancelled();
            }
        };
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.RUN_FAILURE) {
                failureGuards.incrementAndGet();
            }
            return ScheduledGuardDecision.proceed();
        });

        assertThatThrownBy(() -> engine(
                storeWithCredential(),
                sourceExecutor(1, context -> ScheduledDiscoveryResult.withoutCheckpoint()),
                workExecutor(context -> ScheduledWorkResult.completed()),
                policy,
                guard).execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class, failure ->
                        assertThat(failure.category()).isEqualTo(ScheduledFailure.Category.CANCELLED));
        assertThat(failureGuards).hasValue(0);
    }

    @Test
    @DisplayName("凭证探活 fatal 不调用失败 Guard 且在租约释放后原样传播")
    void credentialProbeFatalSkipsFailureGuardAndReleasesLease() throws Exception {
        TestVirtualMachineError fatal = new TestVirtualMachineError("fixture probe fatal");
        AtomicInteger failureGuards = new AtomicInteger();
        ScheduledCredentialPolicy policy = new ScheduledCredentialPolicy() {
            @Override
            public String policyId() {
                return POLICY;
            }

            @Override
            public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context) {
                throw fatal;
            }
        };
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.RUN_FAILURE) {
                failureGuards.incrementAndGet();
            }
            return ScheduledGuardDecision.proceed();
        });
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication = publishExecutionFixture(
                registry,
                sourceExecutor(1, context -> ScheduledDiscoveryResult.withoutCheckpoint()),
                List.of(workExecutor(context -> ScheduledWorkResult.completed())),
                List.of(guard),
                Set.of(WORK),
                Set.of(GUARD));

        // 用包含 fatal policy 的 owner 替换普通 fixture。
        ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow();
        publication = ScheduleCapabilityTestFixture.publish(
                registry,
                bindingBundle(
                        new ScheduleCapabilityOwner("fixture", "fixture-package", 2L),
                        sourceExecutor(1, context -> ScheduledDiscoveryResult.withoutCheckpoint()),
                        workExecutor(context -> ScheduledWorkResult.completed()),
                        policy,
                        guard));

        VirtualMachineError observed;
        try {
            engine(storeWithCredential(), registry,
                    new ScheduleRunState(), new SyncTaskExecutor()).execute(task());
            throw new AssertionError("expected credential probe fatal error");
        } catch (VirtualMachineError failure) {
            observed = failure;
        }

        assertThat(observed).isSameAs(fatal);
        assertThat(failureGuards).hasValue(0);
        FakeScheduleCapabilityAccess.Drain drain =
                ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("失败 Guard 继续时完整保留原始安全失败")
    void continuingFailureGuardPreservesPrimary() throws Exception {
        AtomicInteger failureGuards = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.CHALLENGE,
                    "fixture.primary-challenge",
                    4_321L);
        });
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.RUN_FAILURE) {
                failureGuards.incrementAndGet();
            }
            return ScheduledGuardDecision.proceed();
        });

        assertThatThrownBy(() -> engine(
                storeWithCredential(), source,
                workExecutor(context -> ScheduledWorkResult.completed()),
                credentialPolicy(new AtomicReference<>()), guard).execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ScheduledFailure.Category.CHALLENGE);
                    assertThat(failure.code()).isEqualTo("fixture.primary-challenge");
                    assertThat(failure.retryAfterMillis()).isEqualTo(4_321L);
                });
        assertThat(failureGuards).hasValue(1);
    }

    @Test
    @DisplayName("失败 Guard 撤销后继续决定被拒绝且不覆盖主失败")
    void failureGuardRevokeIsRejectedWithoutReplacingPrimary() throws Exception {
        AtomicInteger failureGuards = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.RETRYABLE_NETWORK,
                    "fixture.primary-failure",
                    2_000L);
        });
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.RUN_FAILURE) {
                failureGuards.incrementAndGet();
                return new ScheduledGuardDecision(
                        ScheduledGuardDecision.Action.REVOKE_CREDENTIAL_AND_CONTINUE,
                        "fixture.failure-revoke",
                        0L);
            }
            return ScheduledGuardDecision.proceed();
        });

        Throwable observed = catchThrowable(() -> engine(
                storeWithCredential(), source,
                workExecutor(context -> ScheduledWorkResult.completed()),
                credentialPolicy(new AtomicReference<>()), guard).execute(task()));

        assertThat(observed).isExactlyInstanceOf(ScheduledExecutionException.class);
        ScheduledExecutionException failure = (ScheduledExecutionException) observed;
        assertThat(failure.category()).isEqualTo(ScheduledFailure.Category.RETRYABLE_NETWORK);
        assertThat(failure.code()).isEqualTo("fixture.primary-failure");
        assertThat(failure.retryAfterMillis()).isEqualTo(2_000L);
        assertThat(failureGuards).hasValue(1);
    }

}
