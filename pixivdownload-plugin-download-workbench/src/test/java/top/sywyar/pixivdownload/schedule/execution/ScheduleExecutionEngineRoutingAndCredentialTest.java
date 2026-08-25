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

@DisplayName("计划执行引擎路由与凭证")
class ScheduleExecutionEngineRoutingAndCredentialTest extends ScheduleExecutionEngineTestSupport {

    @Test
    @DisplayName("运行期共享计划 gate 保留既有分类和机器码并阻止任何执行副作用")
    void runtimeSharedPlanGatePreservesFailureCodes() throws Exception {
        ScheduledGuardBinding normalGuard = new ScheduledGuardBinding(
                GUARD, Set.of(ScheduledGuardPoint.RUN_START), 0);
        List<PlanFailureCase> cases = List.of(
                new PlanFailureCase(
                        null,
                        ScheduledFailure.Category.INTERNAL,
                        "schedule.source.null-plan"),
                new PlanFailureCase(
                        new ScheduledExecutionPlan(
                                Set.of(WORK), POLICY, ScheduledCredentialRequirement.REQUIRED, false,
                                List.of(), null, 0, 257, 0L,
                                ScheduledNetworkRoute.inherit()),
                        ScheduledFailure.Category.INVALID_DEFINITION,
                        "schedule.plan.max-in-flight-too-large"),
                new PlanFailureCase(
                        new ScheduledExecutionPlan(
                                Set.of(WORK), POLICY, ScheduledCredentialRequirement.REQUIRED, false,
                                List.of(new ScheduledGuardBinding(
                                        GUARD, Set.of(ScheduledGuardPoint.WORK_BATCH), 100_001)),
                                null, 0, 1, 0L, ScheduledNetworkRoute.inherit()),
                        ScheduledFailure.Category.INVALID_DEFINITION,
                        "schedule.plan.guard-batch-too-large"),
                new PlanFailureCase(
                        new ScheduledExecutionPlan(
                                Set.of(WORK), POLICY, ScheduledCredentialRequirement.REQUIRED, false,
                                List.of(normalGuard, normalGuard), null, 0, 1, 0L,
                                ScheduledNetworkRoute.inherit()),
                        ScheduledFailure.Category.INVALID_DEFINITION,
                        "schedule.plan.capability-mismatch"),
                new PlanFailureCase(
                        new ScheduledExecutionPlan(
                                Set.of("other-work"), POLICY,
                                ScheduledCredentialRequirement.REQUIRED, false,
                                List.of(), null, 0, 1, 0L,
                                ScheduledNetworkRoute.inherit()),
                        ScheduledFailure.Category.INVALID_DEFINITION,
                        "schedule.plan.capability-mismatch"),
                new PlanFailureCase(
                        new ScheduledExecutionPlan(
                                Set.of(WORK), "other-policy",
                                ScheduledCredentialRequirement.REQUIRED, false,
                                List.of(), null, 0, 1, 0L,
                                ScheduledNetworkRoute.inherit()),
                        ScheduledFailure.Category.INVALID_DEFINITION,
                        "schedule.plan.capability-mismatch"),
                new PlanFailureCase(
                        new ScheduledExecutionPlan(
                                Set.of(WORK), POLICY, ScheduledCredentialRequirement.REQUIRED, false,
                                List.of(new ScheduledGuardBinding(
                                        "other-guard", Set.of(ScheduledGuardPoint.RUN_START), 0)),
                                null, 0, 1, 0L, ScheduledNetworkRoute.inherit()),
                        ScheduledFailure.Category.INVALID_DEFINITION,
                        "schedule.plan.capability-mismatch"));

        for (PlanFailureCase failureCase : cases) {
            ScheduledTaskStore store = mock(ScheduledTaskStore.class);
            AtomicBoolean discovered = new AtomicBoolean();
            ScheduledSourceExecutor source = new ScheduledSourceExecutor() {
                @Override
                public String sourceType() {
                    return SOURCE;
                }

                @Override
                public ScheduledExecutionPlan plan(ScheduledTaskDefinition task) {
                    return failureCase.plan();
                }

                @Override
                public ScheduledDiscoveryResult discover(ScheduledSourceContext context) {
                    discovered.set(true);
                    return ScheduledDiscoveryResult.withoutCheckpoint();
                }
            };

            assertThatThrownBy(() -> engine(
                    store,
                    source,
                    workExecutor(context -> ScheduledWorkResult.completed()),
                    credentialPolicy(new AtomicReference<>()),
                    guard(context -> ScheduledGuardDecision.proceed())).execute(task()))
                    .isInstanceOfSatisfying(ScheduledExecutionException.class, failure -> {
                        assertThat(failure.category()).isEqualTo(failureCase.category());
                        assertThat(failure.code()).isEqualTo(failureCase.code());
                    });
            assertThat(discovered).isFalse();
            verify(store, never()).upsertPendingWork(any());
        }
    }

    @Test
    @DisplayName("绑定探活在复合租约内使用同一 resolved route 且不执行来源作品或 Guard")
    void bindingProbeUsesResolvedRouteOnceWithoutExecutingPlanCapabilities() throws Exception {
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        AtomicBoolean discovered = new AtomicBoolean();
        AtomicBoolean worked = new AtomicBoolean();
        AtomicBoolean guarded = new AtomicBoolean();
        AtomicInteger bindProbes = new AtomicInteger();
        AtomicReference<ScheduledNetworkRoute> route = new AtomicReference<>();
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            discovered.set(true);
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledWorkExecutor work = workExecutor(context -> {
            worked.set(true);
            return ScheduledWorkResult.completed();
        });
        ScheduledCredentialPolicy policy = new ScheduledCredentialPolicy() {
            @Override
            public String policyId() {
                return POLICY;
            }

            @Override
            public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context) {
                throw new AssertionError("binding must use probeForBinding");
            }

            @Override
            public ScheduledCredentialBindResult probeForBinding(
                    ScheduledCredentialContext context) {
                bindProbes.incrementAndGet();
                assertThat(context.purpose()).isEqualTo(ScheduledCredentialContext.Purpose.BIND);
                assertThat(context.route().isResolved()).isTrue();
                assertThat(context.route().mode()).isEqualTo(ScheduledNetworkRoute.Mode.DIRECT);
                route.set(context.route());
                char[] secret = context.credential().copySecret();
                try {
                    assertThat(new String(secret)).isEqualTo("candidate-secret");
                } finally {
                    java.util.Arrays.fill(secret, '\0');
                }
                assertThat(context.credential().reference())
                        .isEqualTo("scheduled-task:1:credential");
                return ScheduledCredentialBindResult.fromProbe(
                        ScheduledCredentialProbeResult.valid("account-1"));
            }
        };
        ScheduledExecutionGuard guard = guard(context -> {
            guarded.set(true);
            return ScheduledGuardDecision.proceed();
        });
        CredentialEngineFixture fixture = credentialEngine(store, source, work, policy, guard);

        try (ScheduleCredentialBindingLease binding = fixture.engine().prepareCredentialBinding(
                task(), fixture.activationToken())) {
            assertThat(binding.policyOwnerPluginId()).isEqualTo("fixture");
            assertThat(binding.policyId()).isEqualTo(POLICY);
            ScheduledCredentialBindResult result = binding.probe("candidate-secret");
            assertThat(result.probeResult().accountKey()).isEqualTo("account-1");
            assertThatThrownBy(() -> binding.probe("candidate-secret"))
                    .isInstanceOf(IllegalStateException.class);
        }

        assertThat(bindProbes).hasValue(1);
        assertThat(route.get()).isNotNull();
        assertThat(discovered).isFalse();
        assertThat(worked).isFalse();
        assertThat(guarded).isFalse();
    }

    @Test
    @DisplayName("来源默认路由同时用于凭证绑定与正式运行的全部插件回调")
    void sourceDefaultRouteIsSharedByBindingAndExecution() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        ScheduledNetworkRoute sourceRoute = ScheduledNetworkRoute.proxy(
                "source.proxy", 9080, "source-reference");
        ScheduledExecutionPlan executionPlan = new ScheduledExecutionPlan(
                Set.of(WORK),
                POLICY,
                ScheduledCredentialRequirement.REQUIRED,
                false,
                List.of(new ScheduledGuardBinding(
                        GUARD, Set.of(ScheduledGuardPoint.RUN_START), 0)),
                null,
                0,
                1,
                0L,
                sourceRoute);
        AtomicReference<ScheduledNetworkRoute> bindingRoute = new AtomicReference<>();
        AtomicReference<ScheduledNetworkRoute> executionRoute = new AtomicReference<>();
        ScheduledSourceExecutor source = sourceWithPlan(executionPlan, context -> {
            assertThat(context.route()).isSameAs(sourceRoute);
            context.workSink().submit(work("source-route"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledWorkExecutor work = workExecutor(context -> {
            assertThat(context.route()).isSameAs(sourceRoute);
            return ScheduledWorkResult.completed();
        });
        ScheduledCredentialPolicy policy = new ScheduledCredentialPolicy() {
            @Override
            public String policyId() {
                return POLICY;
            }

            @Override
            public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context) {
                executionRoute.set(context.route());
                return ScheduledCredentialProbeResult.valid("account-1");
            }

            @Override
            public ScheduledCredentialBindResult probeForBinding(
                    ScheduledCredentialContext context) {
                bindingRoute.set(context.route());
                return ScheduledCredentialBindResult.fromProbe(
                        ScheduledCredentialProbeResult.valid("account-1"));
            }
        };
        ScheduledExecutionGuard guard = guard(context -> {
            assertThat(context.route()).isSameAs(sourceRoute);
            return ScheduledGuardDecision.proceed();
        });
        CredentialEngineFixture fixture = credentialEngine(store, source, work, policy, guard);

        try (ScheduleCredentialBindingLease binding = fixture.engine().prepareCredentialBinding(
                task(), fixture.activationToken())) {
            binding.probe("candidate-secret");
        }
        fixture.engine().execute(task());

        assertThat(bindingRoute.get()).isSameAs(sourceRoute);
        assertThat(executionRoute.get()).isSameAs(sourceRoute);
    }

    @Test
    @DisplayName("合法任务代理在无效来源代理标记之前胜出并用于绑定和执行")
    void taskProxyWinsBeforeInvalidSourceRouteForBindingAndExecution() throws Exception {
        ScheduledNetworkRoute invalidSourceRoute = ScheduledNetworkRoute.proxy(
                "<invalid-source-proxy>", 1, null);
        ScheduledExecutionPlan executionPlan = new ScheduledExecutionPlan(
                Set.of(WORK),
                POLICY,
                ScheduledCredentialRequirement.REQUIRED,
                false,
                List.of(new ScheduledGuardBinding(
                        GUARD, Set.of(ScheduledGuardPoint.RUN_START), 0)),
                null,
                0,
                1,
                0L,
                invalidSourceRoute);
        AtomicReference<ScheduledNetworkRoute> bindingRoute = new AtomicReference<>();
        AtomicReference<ScheduledNetworkRoute> executionRoute = new AtomicReference<>();
        ScheduledSourceExecutor source = sourceWithPlan(executionPlan, context -> {
            assertTaskProxy(context.route());
            assertThat(context.route()).isSameAs(executionRoute.get());
            context.workSink().submit(work("task-proxy"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledWorkExecutor work = workExecutor(context -> {
            assertTaskProxy(context.route());
            assertThat(context.route()).isSameAs(executionRoute.get());
            return ScheduledWorkResult.completed();
        });
        ScheduledCredentialPolicy policy = new ScheduledCredentialPolicy() {
            @Override
            public String policyId() {
                return POLICY;
            }

            @Override
            public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context) {
                assertTaskProxy(context.route());
                executionRoute.set(context.route());
                return ScheduledCredentialProbeResult.valid("account-1");
            }

            @Override
            public ScheduledCredentialBindResult probeForBinding(
                    ScheduledCredentialContext context) {
                assertTaskProxy(context.route());
                bindingRoute.set(context.route());
                return ScheduledCredentialBindResult.fromProbe(
                        ScheduledCredentialProbeResult.valid("account-1"));
            }
        };
        ScheduledExecutionGuard guard = guard(context -> {
            assertTaskProxy(context.route());
            assertThat(context.route()).isSameAs(executionRoute.get());
            return ScheduledGuardDecision.proceed();
        });
        CredentialEngineFixture fixture = credentialEngine(
                storeWithCredential(), source, work, policy, guard);
        ScheduledTask task = taskWithProxy("task.proxy:9080");

        try (ScheduleCredentialBindingLease binding = fixture.engine()
                .prepareCredentialBinding(task, fixture.activationToken())) {
            binding.probe("candidate-secret");
        }
        fixture.engine().execute(task);

        assertTaskProxy(bindingRoute.get());
        assertTaskProxy(executionRoute.get());
    }

    @Test
    @DisplayName("无任务代理时无效来源代理在凭证读取和插件网络回调前拒绝")
    void invalidSourceRouteWithoutTaskProxyFailsBeforeCredentialOrNetwork() throws Exception {
        ScheduledNetworkRoute invalidSourceRoute = ScheduledNetworkRoute.proxy(
                "<invalid-source-proxy>", 1, null);
        ScheduledExecutionPlan executionPlan = new ScheduledExecutionPlan(
                Set.of(WORK),
                POLICY,
                ScheduledCredentialRequirement.REQUIRED,
                false,
                List.of(new ScheduledGuardBinding(
                        GUARD, Set.of(ScheduledGuardPoint.RUN_START), 0)),
                null,
                0,
                1,
                0L,
                invalidSourceRoute);
        AtomicInteger networkCallbacks = new AtomicInteger();
        AtomicInteger credentialProbes = new AtomicInteger();
        ScheduledSourceExecutor source = sourceWithPlan(executionPlan, context -> {
            networkCallbacks.incrementAndGet();
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledWorkExecutor work = workExecutor(context -> {
            networkCallbacks.incrementAndGet();
            return ScheduledWorkResult.completed();
        });
        ScheduledCredentialPolicy policy = new ScheduledCredentialPolicy() {
            @Override
            public String policyId() {
                return POLICY;
            }

            @Override
            public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context) {
                credentialProbes.incrementAndGet();
                return ScheduledCredentialProbeResult.valid("account-1");
            }

            @Override
            public ScheduledCredentialBindResult probeForBinding(
                    ScheduledCredentialContext context) {
                credentialProbes.incrementAndGet();
                return ScheduledCredentialBindResult.fromProbe(
                        ScheduledCredentialProbeResult.valid("account-1"));
            }
        };
        ScheduledExecutionGuard guard = guard(context -> {
            networkCallbacks.incrementAndGet();
            return ScheduledGuardDecision.proceed();
        });
        ScheduledTaskStore store = storeWithCredential();
        CredentialEngineFixture fixture = credentialEngine(
                store, source, work, policy, guard);

        assertThatThrownBy(() -> fixture.engine().prepareCredentialBinding(
                task(), fixture.activationToken()))
                .isInstanceOf(ScheduleDefinitionException.class)
                .hasMessage("invalid schedule network route");
        assertThatThrownBy(() -> fixture.engine().execute(task()))
                .isInstanceOf(ScheduleDefinitionException.class)
                .hasMessage("invalid schedule network route");

        assertThat(credentialProbes).hasValue(0);
        assertThat(networkCallbacks).hasValue(0);
        verify(store, never()).findCredentialSecret(
                anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("绑定探活拒绝策略初始状态中的凭证字段与凭证文本")
    void bindingProbeRejectsCredentialMaterialInInitialPolicyState() throws Exception {
        AtomicReference<String> policyState = new AtomicReference<>();
        ScheduledCredentialPolicy policy = new ScheduledCredentialPolicy() {
            @Override
            public String policyId() {
                return POLICY;
            }

            @Override
            public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context) {
                throw new AssertionError("binding must use probeForBinding");
            }

            @Override
            public ScheduledCredentialBindResult probeForBinding(
                    ScheduledCredentialContext context) {
                return new ScheduledCredentialBindResult(
                        ScheduledCredentialProbeResult.valid("account-1"),
                        policyState.get(), null);
            }
        };
        CredentialEngineFixture fixture = credentialEngine(
                mock(ScheduledTaskStore.class),
                sourceExecutor(1, context -> ScheduledDiscoveryResult.withoutCheckpoint()),
                workExecutor(context -> ScheduledWorkResult.completed()),
                policy,
                guard(context -> ScheduledGuardDecision.proceed()));

        for (String unsafeState : List.of(
                "{\"refresh_token\":\"opaque\"}",
                "{\"tokenCount\":\"opaque-token-value\"}",
                "{\"cookiePresent\":\"opaque-cookie-value\"}",
                "{\"tokenCount!\":\"opaque-token-value\"}",
                "{\"token-co-unt\":\"opaque-token-value\"}",
                "{\"tokenCountValue\":\"opaque-token-value\"}",
                "{\"cookiePresentValue\":\"opaque-cookie-value\"}",
                "{\"sidCountHeader\":\"opaque-session-value\"}",
                "{\"tokenPresentCount\":\"opaque-token-value\"}",
                "{\"cookieEnabledVersion\":\"opaque-cookie-value\"}",
                "{\"sidCountPresent\":\"opaque-session-value\"}",
                "{\"note\":\"tokenCount=opaque-token-value\"}",
                "{\"details\":[{\"note\":\"PHPSESSID=secret\"}]}",
                "{\"note\":\"PHPSESSID=secret\",\"note\":\"safe\"}",
                "{\"note\":\"safe\"} {\"note\":\"PHPSESSID=secret\"}",
                "{\"details\":\"{\\\"cookie\\\":\\\"secret\\\","
                        + "\\\"cookie\\\":\\\"safe\\\"}\"}")) {
            policyState.set(unsafeState);
            try (ScheduleCredentialBindingLease binding =
                         fixture.engine().prepareCredentialBinding(
                                 task(), fixture.activationToken())) {
                assertThatThrownBy(() -> binding.probe("candidate-secret"))
                        .isInstanceOfSatisfying(ScheduledExecutionException.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo("schedule.credential.invalid-policy-state"));
            }
        }

        String safeState = "{\"details\":\"{\\\"kind\\\":\\\"safe\\\"}\","
                + "\"tokenCount\":2,\"cookiePresent\":false}";
        policyState.set(safeState);
        try (ScheduleCredentialBindingLease binding = fixture.engine().prepareCredentialBinding(
                task(), fixture.activationToken())) {
            assertThat(binding.probe("candidate-secret").initialPolicyStateJson())
                    .isEqualTo(safeState);
        }
    }

    @Test
    @DisplayName("绑定探活拒绝账号、机器码、策略状态与 Guard 证据中的原始凭证回显")
    void bindingProbeRejectsExactCredentialEchoAcrossReturnedFields() throws Exception {
        String candidateSecret = "candidate-secret";
        ScheduledCredentialProbeResult safeProbe =
                ScheduledCredentialProbeResult.valid("account-1");
        List<BindingEchoCase> cases = List.of(
                new BindingEchoCase(
                        "accountKey",
                        new ScheduledCredentialBindResult(
                                new ScheduledCredentialProbeResult(
                                        ScheduledCredentialProbeResult.Status.VALID,
                                        candidateSecret,
                                        "credential.valid",
                                        0L),
                                "{}",
                                null),
                        "schedule.credential.invalid-bind-result"),
                new BindingEchoCase(
                        "code",
                        new ScheduledCredentialBindResult(
                                new ScheduledCredentialProbeResult(
                                        ScheduledCredentialProbeResult.Status.VALID,
                                        "account-1",
                                        candidateSecret,
                                        0L),
                                "{}",
                                null),
                        "schedule.credential.invalid-bind-result"),
                new BindingEchoCase(
                        "initialPolicyStateJson",
                        new ScheduledCredentialBindResult(
                                safeProbe,
                                "{\"state\":\"" + candidateSecret + "\"}",
                                null),
                        "schedule.credential.invalid-policy-state"),
                new BindingEchoCase(
                        "postBindEvidence",
                        new ScheduledCredentialBindResult(
                                safeProbe,
                                "{}",
                                new ScheduledGuardResult(
                                        new ScheduledGuardDecision(
                                                ScheduledGuardDecision.Action.SUSPEND_POLICY_TASK,
                                                "fixture.bind-warning",
                                                0L),
                                        new ScheduledGuardEvidence(
                                                Map.of("excerpt", candidateSecret)))),
                        "schedule.credential.invalid-bind-result"));

        for (BindingEchoCase echoCase : cases) {
            ScheduledCredentialPolicy policy = new ScheduledCredentialPolicy() {
                @Override
                public String policyId() {
                    return POLICY;
                }

                @Override
                public ScheduledCredentialProbeResult probe(
                        ScheduledCredentialContext context) {
                    throw new AssertionError("binding must use probeForBinding");
                }

                @Override
                public ScheduledCredentialBindResult probeForBinding(
                        ScheduledCredentialContext context) {
                    return echoCase.result();
                }
            };
            CredentialEngineFixture fixture = credentialEngine(
                    mock(ScheduledTaskStore.class),
                    sourceExecutor(
                            1,
                            context -> ScheduledDiscoveryResult.withoutCheckpoint()),
                    workExecutor(context -> ScheduledWorkResult.completed()),
                    policy,
                    guard(context -> ScheduledGuardDecision.proceed()));

            try (ScheduleCredentialBindingLease binding =
                         fixture.engine().prepareCredentialBinding(
                                 task(), fixture.activationToken())) {
                assertThatThrownBy(() -> binding.probe(candidateSecret))
                        .as(echoCase.field())
                        .isInstanceOfSatisfying(
                                ScheduledExecutionException.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(echoCase.expectedCode()));
            }
        }
    }

    @Test
    @DisplayName("旧 activation token 在新 publication 上于来源回调前拒绝且不探活凭证")
    void staleActivationTokenNeverReachesReplacementSourceOrPolicy() throws Exception {
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        ScheduledWorkExecutor work = workExecutor(context -> ScheduledWorkResult.completed());
        ScheduledExecutionGuard guard = guard(context -> ScheduledGuardDecision.proceed());
        ScheduledSourceExecutor sourceA = sourceWithPlan(
                plan(Set.of(WORK), List.of(new ScheduledGuardBinding(
                        GUARD, Set.of(ScheduledGuardPoint.RUN_START), 0))),
                context -> ScheduledDiscoveryResult.withoutCheckpoint());
        FakeScheduleCapabilityAccess.Publication publicationA =
                ScheduleCapabilityTestFixture.publish(
                        registry,
                        bindingBundle(
                                new ScheduleCapabilityOwner(
                                        "fixture", "fixture-package", 1L),
                                sourceA, work, bindingPolicy(new AtomicInteger()), guard));
        String activationA = activationToken(registry);
        FakeScheduleCapabilityAccess.Drain drainA =
                ScheduleCapabilityTestFixture.withdraw(registry, publicationA).orElseThrow();
        assertThat(drainA.isDrained()).isTrue();

        AtomicInteger replacementPlans = new AtomicInteger();
        AtomicInteger replacementProbes = new AtomicInteger();
        ScheduledSourceExecutor sourceB = new ScheduledSourceExecutor() {
            @Override
            public String sourceType() {
                return SOURCE;
            }

            @Override
            public ScheduledExecutionPlan plan(ScheduledTaskDefinition task) {
                replacementPlans.incrementAndGet();
                return ScheduleExecutionEngineTestSupport.plan(
                        Set.of(WORK), List.of(new ScheduledGuardBinding(
                                GUARD, Set.of(ScheduledGuardPoint.RUN_START), 0)));
            }

            @Override
            public ScheduledDiscoveryResult discover(ScheduledSourceContext context) {
                return ScheduledDiscoveryResult.withoutCheckpoint();
            }
        };
        ScheduleCapabilityTestFixture.publish(
                registry,
                bindingBundle(
                        new ScheduleCapabilityOwner("fixture", "fixture-package", 2L),
                        sourceB, work, bindingPolicy(replacementProbes), guard));
        ScheduleExecutionEngine engine = engine(
                mock(ScheduledTaskStore.class), registry,
                new ScheduleRunState(), new SyncTaskExecutor());

        assertThatThrownBy(() -> engine.prepareCredentialBinding(task(), activationA))
                .isInstanceOf(ScheduleSourcePublicationChangedException.class);
        assertThat(replacementPlans).hasValue(0);
        assertThat(replacementProbes).hasValue(0);
    }

    @Test
    @DisplayName("已取得旧 publication 复合租约后切换代际不会把凭证交给任一策略")
    void acquiredBindingLeaseIsCancelledAcrossPublicationSwitchBeforeProbe() throws Exception {
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        ScheduledWorkExecutor work = workExecutor(context -> ScheduledWorkResult.completed());
        ScheduledExecutionGuard guard = guard(context -> ScheduledGuardDecision.proceed());
        ScheduledExecutionPlan executionPlan = plan(
                Set.of(WORK), List.of(new ScheduledGuardBinding(
                        GUARD, Set.of(ScheduledGuardPoint.RUN_START), 0)));
        AtomicInteger oldProbes = new AtomicInteger();
        FakeScheduleCapabilityAccess.Publication publicationA =
                ScheduleCapabilityTestFixture.publish(
                        registry,
                        bindingBundle(
                                new ScheduleCapabilityOwner(
                                        "fixture", "fixture-package", 1L),
                                sourceWithPlan(
                                        executionPlan,
                                        context -> ScheduledDiscoveryResult.withoutCheckpoint()),
                                work, bindingPolicy(oldProbes), guard));
        String activationA = activationToken(registry);
        ScheduleExecutionEngine engine = engine(
                mock(ScheduledTaskStore.class), registry,
                new ScheduleRunState(), new SyncTaskExecutor());

        FakeScheduleCapabilityAccess.Drain drainA;
        AtomicInteger replacementPlans = new AtomicInteger();
        AtomicInteger replacementProbes = new AtomicInteger();
        try (ScheduleCredentialBindingLease binding = engine.prepareCredentialBinding(
                task(), activationA)) {
            drainA = ScheduleCapabilityTestFixture.withdraw(
                    registry, publicationA).orElseThrow();
            ScheduledSourceExecutor sourceB = new ScheduledSourceExecutor() {
                @Override
                public String sourceType() {
                    return SOURCE;
                }

                @Override
                public ScheduledExecutionPlan plan(ScheduledTaskDefinition task) {
                    replacementPlans.incrementAndGet();
                    return executionPlan;
                }

                @Override
                public ScheduledDiscoveryResult discover(ScheduledSourceContext context) {
                    return ScheduledDiscoveryResult.withoutCheckpoint();
                }
            };
            ScheduleCapabilityTestFixture.publish(
                    registry,
                    bindingBundle(
                            new ScheduleCapabilityOwner("fixture", "fixture-package", 2L),
                            sourceB, work, bindingPolicy(replacementProbes), guard));

            assertThat(drainA.isDrained()).isFalse();
            assertThatThrownBy(() -> binding.probe("candidate-secret"))
                    .isInstanceOfSatisfying(ScheduledExecutionException.class, failure -> {
                        assertThat(failure.category()).isEqualTo(ScheduledFailure.Category.CANCELLED);
                        assertThat(failure.code()).isEqualTo("schedule.cancelled");
                    });
            assertThat(oldProbes).hasValue(0);
            assertThat(replacementPlans).hasValue(0);
            assertThat(replacementProbes).hasValue(0);
        }
        assertThat(drainA.isDrained()).isTrue();
    }

}
