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
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
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
import top.sywyar.pixivdownload.schedule.ScheduleConfig;
import top.sywyar.pixivdownload.schedule.ScheduleDefinitionException;
import top.sywyar.pixivdownload.schedule.ScheduleRunQueue;
import top.sywyar.pixivdownload.schedule.ScheduleRunState;
import top.sywyar.pixivdownload.schedule.ScheduleSourcePublicationChangedException;
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

@DisplayName("通用计划任务执行引擎")
class ScheduleExecutionEngineTest {

    private static final String SOURCE = "fixture-source";
    private static final String WORK = "fixture-work";
    private static final String POLICY = "fixture-policy";
    private static final String GUARD = "fixture-guard";

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

        String safeState = "{\"details\":\"{\\\"kind\\\":\\\"safe\\\"}\"}";
        policyState.set(safeState);
        try (ScheduleCredentialBindingLease binding = fixture.engine().prepareCredentialBinding(
                task(), fixture.activationToken())) {
            assertThat(binding.probe("candidate-secret").initialPolicyStateJson())
                    .isEqualTo(safeState);
        }
    }

    @Test
    @DisplayName("旧 activation token 在新 publication 上于来源回调前拒绝且不探活凭证")
    void staleActivationTokenNeverReachesReplacementSourceOrPolicy() throws Exception {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduledWorkExecutor work = workExecutor(context -> ScheduledWorkResult.completed());
        ScheduledExecutionGuard guard = guard(context -> ScheduledGuardDecision.proceed());
        ScheduledSourceExecutor sourceA = sourceWithPlan(
                plan(Set.of(WORK), List.of(new ScheduledGuardBinding(
                        GUARD, Set.of(ScheduledGuardPoint.RUN_START), 0))),
                context -> ScheduledDiscoveryResult.withoutCheckpoint());
        ScheduleCapabilityPublication publicationA = ScheduleCapabilityRegistryTestAccess.publish(
                registry,
                bindingBundle(
                        new ScheduleCapabilityOwner("fixture", "fixture-package", 1L),
                        sourceA, work, bindingPolicy(new AtomicInteger()), guard));
        String activationA = activationToken(registry);
        ScheduleGenerationDrain drainA = ScheduleCapabilityRegistryTestAccess.withdraw(
                registry, publicationA).orElseThrow();
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
                return ScheduleExecutionEngineTest.plan(
                        Set.of(WORK), List.of(new ScheduledGuardBinding(
                                GUARD, Set.of(ScheduledGuardPoint.RUN_START), 0)));
            }

            @Override
            public ScheduledDiscoveryResult discover(ScheduledSourceContext context) {
                return ScheduledDiscoveryResult.withoutCheckpoint();
            }
        };
        ScheduleCapabilityRegistryTestAccess.publish(
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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduledWorkExecutor work = workExecutor(context -> ScheduledWorkResult.completed());
        ScheduledExecutionGuard guard = guard(context -> ScheduledGuardDecision.proceed());
        ScheduledExecutionPlan executionPlan = plan(
                Set.of(WORK), List.of(new ScheduledGuardBinding(
                        GUARD, Set.of(ScheduledGuardPoint.RUN_START), 0)));
        AtomicInteger oldProbes = new AtomicInteger();
        ScheduleCapabilityPublication publicationA = ScheduleCapabilityRegistryTestAccess.publish(
                registry,
                bindingBundle(
                        new ScheduleCapabilityOwner("fixture", "fixture-package", 1L),
                        sourceWithPlan(
                                executionPlan,
                                context -> ScheduledDiscoveryResult.withoutCheckpoint()),
                        work, bindingPolicy(oldProbes), guard));
        String activationA = activationToken(registry);
        ScheduleExecutionEngine engine = engine(
                mock(ScheduledTaskStore.class), registry,
                new ScheduleRunState(), new SyncTaskExecutor());

        ScheduleGenerationDrain drainA;
        AtomicInteger replacementPlans = new AtomicInteger();
        AtomicInteger replacementProbes = new AtomicInteger();
        try (ScheduleCredentialBindingLease binding = engine.prepareCredentialBinding(
                task(), activationA)) {
            drainA = ScheduleCapabilityRegistryTestAccess.withdraw(
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
            ScheduleCapabilityRegistryTestAccess.publish(
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
    @DisplayName("作品类型容量在任务并发之内独立施加背压")
    void workTypeConcurrencyLimitAppliesInsidePlanLimit() throws Exception {
        CountDownLatch firstTwoEntered = new CountDownLatch(2);
        CountDownLatch thirdSubmitAttempted = new CountDownLatch(1);
        CountDownLatch releaseFirstTwo = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(6, context -> {
            for (int i = 1; i <= 6; i++) {
                if (i == 3) {
                    thirdSubmitAttempted.countDown();
                }
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
            public int maxConcurrency() {
                return 2;
            }

            @Override
            public ScheduledWorkResult execute(
                    ScheduledWork work,
                    ScheduledWorkContext context) throws ScheduledExecutionException {
                int sequence = started.incrementAndGet();
                int current = active.incrementAndGet();
                peak.accumulateAndGet(current, Math::max);
                try {
                    if (sequence <= 2) {
                        firstTwoEntered.countDown();
                        if (!releaseFirstTwo.await(5, TimeUnit.SECONDS)) {
                            throw new ScheduledExecutionException(
                                    ScheduledFailure.Category.INTERNAL,
                                    "fixture.release-timeout");
                        }
                    }
                    return ScheduledWorkResult.completed();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw ScheduledExecutionException.cancelled();
                } finally {
                    active.decrementAndGet();
                }
            }
        };
        ExecutorService workers = Executors.newFixedThreadPool(6);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            ScheduleExecutionEngine engine = engine(
                    storeWithCredential(), source, executor,
                    credentialPolicy(new AtomicReference<>()),
                    guard(context -> ScheduledGuardDecision.proceed()),
                    new ScheduleRunState(), workers::execute);
            Future<ScheduleExecutionResult> execution = caller.submit(() -> engine.execute(task()));

            assertThat(firstTwoEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(thirdSubmitAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(started).hasValue(2);
            assertThat(peak).hasValue(2);

            releaseFirstTwo.countDown();
            assertThat(execution.get(5, TimeUnit.SECONDS).completedWorkCount()).isEqualTo(6);
            assertThat(peak).hasValue(2);
        } finally {
            releaseFirstTwo.countDown();
            workers.shutdownNow();
            caller.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
            caller.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("作品失败先完整写入 pending 才允许末尾 Guard 与 checkpoint 候选返回")
    void persistsPendingBeforeEndGuard() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        AtomicBoolean persisted = new AtomicBoolean();
        AtomicReference<ScheduledPendingWork> persistedWork = new AtomicReference<>();
        when(store.upsertPendingWork(any())).thenAnswer(invocation -> {
            persisted.set(true);
            persistedWork.set(invocation.getArgument(0));
            return 1;
        });
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            context.workSink().submit(work("opaque-001"));
            return ScheduledDiscoveryResult.withCheckpoint(
                    new ScheduledCheckpoint("fixture.checkpoint", 1, "{}"));
        });
        ScheduledWorkExecutor executor = workExecutor(context -> {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.RETRYABLE_NETWORK, "fixture.retry");
        });
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.RUN_END) {
                assertThat(persisted).isTrue();
            }
            return ScheduledGuardDecision.proceed();
        });

        ScheduleExecutionResult result = engine(
                store, source, executor, credentialPolicy(new AtomicReference<>()), guard)
                .execute(task());

        assertThat(result.completedWorkCount()).isZero();
        assertThat(result.candidateCheckpoint()).isNotNull();
        assertThat(persistedWork.get().attempts()).isZero();
        assertThat(persistedWork.get().firstSeenTime()).isNotNull();
        assertThat(persistedWork.get().lastAttemptTime()).isNull();
        verify(store).upsertPendingWork(any());
    }

    @Test
    @DisplayName("来源可先用纯本地终态清理 pending 且不计作品尝试或批次 Guard")
    void sourceClearsLocallyCompletedPendingBeforeReplay() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        ObjectMapper objectMapper = new ObjectMapper();
        ScheduleWorkPersistenceCodec codec = new ScheduleWorkPersistenceCodec(objectMapper);
        ScheduledWork pendingWork = work("pending-001");
        ScheduledPendingWork pending = codec.toPendingWork(
                1L, pendingWork, "fixture.retry", "{}", 1, 1L, 2L);
        when(store.listPendingWork(1L)).thenReturn(List.of(pending));
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger batchGuards = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(
                1, ScheduledPendingReplayPolicy.REDISCOVERED_ONLY, context -> {
            assertThat(context.isPending(pendingWork.key())).isTrue();
            context.workSink().completeLocally(
                    pendingWork, ScheduledWorkResult.alreadyCompleted());
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledWorkExecutor executor = workExecutor(context -> {
            executions.incrementAndGet();
            return ScheduledWorkResult.completed();
        });
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.WORK_BATCH) {
                batchGuards.incrementAndGet();
            }
            return ScheduledGuardDecision.proceed();
        });

        ScheduleExecutionResult result = engine(
                store, source, executor, credentialPolicy(new AtomicReference<>()), guard)
                .execute(task());

        assertThat(result.completedWorkCount()).isZero();
        assertThat(executions).hasValue(0);
        assertThat(batchGuards).hasValue(0);
        verify(store).deletePendingWork(1L, WORK, "pending-001");
        verify(store, never()).upsertPendingWork(any());
    }

    @Test
    @DisplayName("普通来源失败前仍先执行孤立 pending 并耐久清理成功项")
    void alwaysPendingRunsBeforeSourceFailure() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        ScheduleWorkPersistenceCodec codec =
                new ScheduleWorkPersistenceCodec(new ObjectMapper());
        ScheduledWork pendingWork = work("retry-before-source");
        when(store.listPendingWork(1L)).thenReturn(List.of(codec.toPendingWork(
                1L, pendingWork, "fixture.retry", "{}", 1, 1L, 2L)));
        AtomicInteger executions = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.RETRYABLE_NETWORK,
                    "fixture.source-unavailable");
        });
        ScheduledWorkExecutor executor = workExecutor(context -> {
            executions.incrementAndGet();
            return ScheduledWorkResult.completed();
        });

        assertThatThrownBy(() -> engine(
                store, source, executor,
                credentialPolicy(new AtomicReference<>()),
                guard(context -> ScheduledGuardDecision.proceed())).execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("fixture.source-unavailable"));

        assertThat(executions).hasValue(1);
        verify(store).deletePendingWork(1L, WORK, "retry-before-source");
        verify(store, never()).upsertPendingWork(any());
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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication = publishExecutionFixture(
                registry,
                sourceExecutor(1, context -> ScheduledDiscoveryResult.withoutCheckpoint()),
                List.of(workExecutor(context -> ScheduledWorkResult.completed())),
                List.of(guard),
                Set.of(WORK),
                Set.of(GUARD));

        // 用包含 fatal policy 的 owner 替换普通 fixture。
        ScheduleCapabilityRegistryTestAccess.withdraw(registry, publication).orElseThrow();
        publication = ScheduleCapabilityRegistryTestAccess.publish(
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
        ScheduleGenerationDrain drain = ScheduleCapabilityRegistryTestAccess.withdraw(
                registry, publication).orElseThrow();
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

    @Test
    @DisplayName("持久化检查点异常在凭证读取和来源发现前失败")
    void invalidStoredCheckpointFailsBeforeCredentialOrDiscovery() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        AtomicInteger discoveries = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            discoveries.incrementAndGet();
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduleExecutionEngine engine = engine(
                store, source,
                workExecutor(context -> ScheduledWorkResult.completed()),
                credentialPolicy(new AtomicReference<>()),
                guard(context -> ScheduledGuardDecision.proceed()));

        assertThatThrownBy(() -> engine.execute(
                taskWithCheckpoint("other.checkpoint", 1, "{}")))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.checkpoint.plan-mismatch"));
        assertThatThrownBy(() -> engine.execute(
                taskWithCheckpoint("fixture.checkpoint", null, "{}")))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.checkpoint.invalid-envelope"));
        assertThatThrownBy(() -> engine.execute(
                taskWithCheckpoint("fixture.checkpoint", 1, "not-json")))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.checkpoint.payload-invalid"));
        assertThatThrownBy(() -> engine.execute(
                taskWithCheckpoint("fixture.checkpoint", 1,
                        "{\"cookie\":\"PHPSESSID=secret\"}")))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.checkpoint.payload-invalid"));

        assertThat(discoveries).hasValue(0);
        verify(store, never()).listPendingWork(anyLong());
        verify(store, never()).findCredentialSecret(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("来源候选检查点必须是无凭证材料的单一 JSON")
    void candidateCheckpointMustBeSafeJson() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        AtomicInteger failureGuards = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(1, context ->
                ScheduledDiscoveryResult.withCheckpoint(new ScheduledCheckpoint(
                        "fixture.checkpoint", 1,
                        "{\"token\":\"Bearer secret-value\"}")));
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.RUN_FAILURE) {
                failureGuards.incrementAndGet();
            }
            return ScheduledGuardDecision.proceed();
        });

        assertThatThrownBy(() -> engine(
                store, source,
                workExecutor(context -> ScheduledWorkResult.completed()),
                credentialPolicy(new AtomicReference<>()), guard).execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.checkpoint.payload-invalid"));
        assertThat(failureGuards).hasValue(1);
    }

    @Test
    @DisplayName("不可迁移作品失败先耐久写入 pending 再阻止末尾 Guard 与 checkpoint")
    void unsupportedWorkBecomesTerminalAfterDurablePending() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        when(store.upsertPendingWork(any())).thenReturn(1);
        AtomicInteger endGuards = new AtomicInteger();
        AtomicInteger failureGuards = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            context.workSink().submit(work("unsupported"));
            return ScheduledDiscoveryResult.withCheckpoint(
                    new ScheduledCheckpoint("fixture.checkpoint", 1, "{}"));
        });
        ScheduledWorkExecutor executor = workExecutor(context -> {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                    "fixture.payload-unsupported");
        });
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.RUN_END) {
                endGuards.incrementAndGet();
            }
            if (context.point() == ScheduledGuardPoint.RUN_FAILURE) {
                failureGuards.incrementAndGet();
            }
            return ScheduledGuardDecision.proceed();
        });

        assertThatThrownBy(() -> engine(
                store, source, executor,
                credentialPolicy(new AtomicReference<>()), guard).execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("fixture.payload-unsupported"));
        verify(store).upsertPendingWork(any());
        assertThat(endGuards).hasValue(0);
        assertThat(failureGuards).hasValue(1);
    }

    @Test
    @DisplayName("pending 跨过重试上限后即使末尾 Guard 失败仍上报事件")
    void pendingExhaustionEventSurvivesLaterFailure() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        ScheduleWorkPersistenceCodec codec = new ScheduleWorkPersistenceCodec(new ObjectMapper());
        ScheduledPendingWork pending = codec.toPendingWork(
                1L, work("retry-001"), "fixture.retry", "{}", 4, 1L, 2L);
        when(store.listPendingWork(1L)).thenReturn(List.of(pending));
        when(store.upsertPendingWork(any())).thenReturn(1);
        ScheduledSourceExecutor source = sourceExecutor(
                1, context -> ScheduledDiscoveryResult.withoutCheckpoint());
        ScheduledWorkExecutor executor = workExecutor(context -> {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.RETRYABLE_NETWORK, "fixture.retry");
        });
        ScheduledExecutionGuard guard = guard(context ->
                context.point() == ScheduledGuardPoint.RUN_END
                        ? new ScheduledGuardDecision(
                        ScheduledGuardDecision.Action.FAIL, "fixture.end-rejected", 0L)
                        : ScheduledGuardDecision.proceed());
        List<ScheduleExecutionResult.PendingExhausted> events = new ArrayList<>();

        assertThatThrownBy(() -> engine(
                store, source, executor,
                credentialPolicy(new AtomicReference<>()), guard)
                .execute(task(), events::add))
                .isInstanceOf(ScheduleExecutionControlException.class)
                .hasMessage("fixture.end-rejected");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.workId()).isEqualTo("retry-001");
            assertThat(event.attempts()).isEqualTo(5);
        });
    }

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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityOwner owner = new ScheduleCapabilityOwner(
                "fixture", "fixture-package", 1L);
        ScheduledSourceDescriptor descriptor = new ScheduledSourceDescriptor(
                SOURCE, Set.of(), "fixture.definition", 1,
                new ScheduledSourcePresentation(
                        "fixture", "source.label", "source.summary", "schedule", "neutral"),
                Set.of("fixture"), Set.of(WORK), Set.of(POLICY), Set.of(GUARD), null);
        ScheduleCapabilityPublication publication = ScheduleCapabilityRegistryTestAccess.publish(
                registry, ScheduleOwnerBundle.prepare(
                        owner, List.of(), List.of(), List.of(descriptor), List.of(source),
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
            ScheduleGenerationDrain drain = ScheduleCapabilityRegistryTestAccess.withdraw(
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
        AtomicReference<ScheduleCapabilityPublication> publication = new AtomicReference<>();
        AtomicReference<ScheduleGenerationDrain> withdrawn = new AtomicReference<>();
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
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
                withdrawn.set(ScheduleCapabilityRegistryTestAccess.withdraw(
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
        publication.set(ScheduleCapabilityRegistryTestAccess.publish(
                registry, ScheduleOwnerBundle.prepare(
                        owner, List.of(), List.of(), List.of(descriptor), List.of(source),
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
    @DisplayName("作品池拒绝派发时先写 pending 再失败且不遗留在途计数")
    void rejectedDispatchIsPersistedBeforeFailure() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        when(store.upsertPendingWork(any())).thenReturn(1);
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            context.workSink().submit(work("rejected"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        TaskExecutor rejectingExecutor = task -> {
            throw new IllegalStateException("executor stopped");
        };
        ScheduleExecutionEngine engine = engine(
                store, source,
                workExecutor(context -> ScheduledWorkResult.completed()),
                credentialPolicy(new AtomicReference<>()),
                guard(context -> ScheduledGuardDecision.proceed()),
                new ScheduleRunState(), rejectingExecutor);

        assertThatThrownBy(() -> engine.execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.work.dispatch-failed"));
        verify(store).upsertPendingWork(any());
    }

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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        publish(registry, ScheduleOwnerBundle.prepare(
                new ScheduleCapabilityOwner("fixture", "fixture-package", 1L),
                List.of(), List.of(), List.of(descriptor), List.of(source),
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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication = publishExecutionFixture(
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
        ScheduleGenerationDrain drain = ScheduleCapabilityRegistryTestAccess.withdraw(
                registry, publication).orElseThrow();
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
            ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
            ScheduleCapabilityPublication publication = publishExecutionFixture(
                    registry, source, List.of(work), List.of(), Set.of(WORK), Set.of());
            ScheduledTaskStore store = storeWithCredential();

            Throwable observed = catchThrowable(() -> engine(
                    store, registry,
                    new ScheduleRunState(), new SyncTaskExecutor()).execute(task()));

            assertThat(observed).isSameAs(fatal);
            assertThat(aborts).hasValue(1);
            verify(store).upsertPendingWork(any());
            ScheduleGenerationDrain drain = ScheduleCapabilityRegistryTestAccess.withdraw(
                    registry, publication).orElseThrow();
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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication = publishExecutionFixture(
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
        ScheduleGenerationDrain drain = ScheduleCapabilityRegistryTestAccess.withdraw(
                registry, publication).orElseThrow();
        assertThat(drain.isDrained()).isTrue();
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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication = publishExecutionFixture(
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
        ScheduleGenerationDrain drain = ScheduleCapabilityRegistryTestAccess.withdraw(
                registry, publication).orElseThrow();
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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication = publishExecutionFixture(
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
        ScheduleGenerationDrain drain = ScheduleCapabilityRegistryTestAccess.withdraw(
                registry, publication).orElseThrow();
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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication = publishExecutionFixture(
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
        ScheduleGenerationDrain drain = ScheduleCapabilityRegistryTestAccess.withdraw(
                registry, publication).orElseThrow();
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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication = publishExecutionFixture(
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
        ScheduleGenerationDrain drain = ScheduleCapabilityRegistryTestAccess.withdraw(
                registry, publication).orElseThrow();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("来源 work 在进入队列前拒绝凭证材料")
    void sourceWorkIsValidatedBeforeQueueing() throws Exception {
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            context.workSink().submit(new ScheduledWork(
                    new ScheduledWorkKey(WORK, "unsafe"),
                    "fixture.work", 1,
                    "{\"cookie\":\"PHPSESSID=secret\"}",
                    ScheduledWorkPresentation.empty(), List.of()));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledTaskStore store = storeWithCredential();

        assertThatThrownBy(() -> engine(
                store, source,
                workExecutor(context -> ScheduledWorkResult.completed()),
                credentialPolicy(new AtomicReference<>()),
                guard(context -> ScheduledGuardDecision.proceed())).execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.work.payload-invalid"));
        verify(store, never()).upsertPendingWork(any());
    }

    @Test
    @DisplayName("作品结果属性含凭证形态时转安全终止失败且不进入队列投影")
    void unsafeWorkResultIsRejected() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        when(store.upsertPendingWork(any())).thenReturn(1);
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            context.workSink().submit(work("unsafe-result"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledWorkExecutor executor = workExecutor(context -> new ScheduledWorkResult(
                ScheduledWorkResult.Outcome.COMPLETED,
                "fixture.completed",
                Map.of("title", "Cookie: PHPSESSID=secret")));

        assertThatThrownBy(() -> engine(
                store, source, executor,
                credentialPolicy(new AtomicReference<>()),
                guard(context -> ScheduledGuardDecision.proceed())).execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.work.invalid-result"));
        verify(store).upsertPendingWork(any());
    }

    private static ScheduleExecutionEngine engine(
            ScheduledTaskStore store,
            ScheduledSourceExecutor source,
            ScheduledWorkExecutor work,
            ScheduledCredentialPolicy policy,
            ScheduledExecutionGuard guard) throws Exception {
        return credentialEngine(store, source, work, policy, guard).engine();
    }

    private static CredentialEngineFixture credentialEngine(
            ScheduledTaskStore store,
            ScheduledSourceExecutor source,
            ScheduledWorkExecutor work,
            ScheduledCredentialPolicy policy,
            ScheduledExecutionGuard guard) throws Exception {
        return credentialEngine(store, source, work, policy, guard,
                new ScheduleRunState(), new SyncTaskExecutor());
    }

    private static ScheduleExecutionEngine engine(
            ScheduledTaskStore store,
            ScheduledSourceExecutor source,
            ScheduledWorkExecutor work,
            ScheduledCredentialPolicy policy,
            ScheduledExecutionGuard guard,
            ScheduleRunState runState,
            TaskExecutor taskExecutor) throws Exception {
        return credentialEngine(
                store, source, work, policy, guard, runState, taskExecutor).engine();
    }

    private static CredentialEngineFixture credentialEngine(
            ScheduledTaskStore store,
            ScheduledSourceExecutor source,
            ScheduledWorkExecutor work,
            ScheduledCredentialPolicy policy,
            ScheduledExecutionGuard guard,
            ScheduleRunState runState,
            TaskExecutor taskExecutor) throws Exception {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityOwner owner = new ScheduleCapabilityOwner("fixture", "fixture-package", 1L);
        publish(registry, bindingBundle(owner, source, work, policy, guard));
        return new CredentialEngineFixture(
                engine(store, registry, runState, taskExecutor),
                activationToken(registry));
    }

    private static void publish(
            ScheduleCapabilityRegistry registry,
            ScheduleOwnerBundle bundle) {
        ScheduleCapabilityRegistryTestAccess.publish(registry, bundle);
    }

    private static ScheduleOwnerBundle bindingBundle(
            ScheduleCapabilityOwner owner,
            ScheduledSourceExecutor source,
            ScheduledWorkExecutor work,
            ScheduledCredentialPolicy policy,
            ScheduledExecutionGuard guard) {
        ScheduledSourceDescriptor descriptor = new ScheduledSourceDescriptor(
                SOURCE, Set.of(), "fixture.definition", 1,
                new ScheduledSourcePresentation(
                        "fixture", "source.label", "source.summary", "schedule", "neutral"),
                Set.of("fixture"), Set.of(WORK), Set.of(POLICY), Set.of(GUARD), null);
        return ScheduleOwnerBundle.prepare(
                owner, List.of(), List.of(), List.of(descriptor), List.of(source),
                List.of(work), List.of(policy), List.of(guard));
    }

    private static String activationToken(ScheduleCapabilityRegistry registry) {
        return registry.snapshotView().owners().stream()
                .filter(owner -> owner.owner().featurePluginId().equals("fixture"))
                .findFirst()
                .orElseThrow()
                .activationToken();
    }

    private static ScheduleExecutionEngine engine(
            ScheduledTaskStore store,
            ScheduleCapabilityRegistry registry,
            ScheduleRunState runState,
            TaskExecutor taskExecutor) {
        ScheduleConfig config = new ScheduleConfig();
        config.setPendingMaxAttempts(5);
        config.setAuthFailureCircuitBreaker(5);
        ObjectMapper objectMapper = new ObjectMapper();
        OutboundProxySettings direct = new OutboundProxySettings() {
            @Override
            public boolean isEnabled() {
                return false;
            }

            @Override
            public String getHost() {
                return null;
            }

            @Override
            public int getPort() {
                return 0;
            }
        };
        return new ScheduleExecutionEngine(
                store, registry, runState, new ScheduleRunQueue(), config,
                new ScheduleWorkPersistenceCodec(objectMapper),
                new ScheduleNetworkRouteResolver(direct), taskExecutor, objectMapper);
    }

    private static ScheduledWorkExecutor finalizingExecutor(
            String workType,
            List<String> events,
            boolean fail) {
        return new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return workType;
            }

            @Override
            public ScheduledWorkResult execute(ScheduledWork work, ScheduledWorkContext context) {
                return ScheduledWorkResult.completed();
            }

            @Override
            public void finishRun(ScheduledWorkRunContext context)
                    throws ScheduledExecutionException {
                events.add("finalizer-" + workType);
                if (fail) {
                    throw new ScheduledExecutionException(
                            ScheduledFailure.Category.INTERNAL,
                            "fixture.finalizer-failed");
                }
            }

            @Override
            public void abortRun(ScheduledTaskDefinition task) {
                events.add("abort-" + workType);
                if (fail) {
                    throw new IllegalStateException("fixture cleanup failed");
                }
            }
        };
    }

    private static ScheduledExecutionPlan plan(
            Set<String> workTypes,
            List<ScheduledGuardBinding> guards) {
        return new ScheduledExecutionPlan(
                workTypes,
                POLICY,
                ScheduledCredentialRequirement.REQUIRED,
                false,
                guards,
                null,
                0,
                1,
                0L,
                ScheduledNetworkRoute.inherit());
    }

    private static ScheduledSourceExecutor sourceWithPlan(
            ScheduledExecutionPlan plan,
            Discovery discovery) {
        return new ScheduledSourceExecutor() {
            @Override
            public String sourceType() {
                return SOURCE;
            }

            @Override
            public ScheduledExecutionPlan plan(ScheduledTaskDefinition task) {
                return plan;
            }

            @Override
            public ScheduledDiscoveryResult discover(ScheduledSourceContext context)
                    throws ScheduledExecutionException {
                return discovery.discover(context);
            }
        };
    }

    private static ScheduleCapabilityPublication publishExecutionFixture(
            ScheduleCapabilityRegistry registry,
            ScheduledSourceExecutor source,
            List<ScheduledWorkExecutor> workExecutors,
            List<ScheduledExecutionGuard> guards,
            Set<String> workTypes,
            Set<String> guardIds) {
        ScheduledSourceDescriptor descriptor = new ScheduledSourceDescriptor(
                SOURCE,
                Set.of(),
                "fixture.definition",
                1,
                new ScheduledSourcePresentation(
                        "fixture", "source.label", "source.summary", "schedule", "neutral"),
                Set.of("fixture"),
                workTypes,
                Set.of(POLICY),
                guardIds,
                null);
        return ScheduleCapabilityRegistryTestAccess.publish(
                registry,
                ScheduleOwnerBundle.prepare(
                        new ScheduleCapabilityOwner("fixture", "fixture-package", 1L),
                        List.of(),
                        List.of(),
                        List.of(descriptor),
                        List.of(source),
                        workExecutors,
                        List.of(credentialPolicy(new AtomicReference<>())),
                        guards));
    }

    private static ScheduledWorkExecutor abortingExecutor(
            String workType,
            VirtualMachineError failure,
            List<String> aborts) {
        return new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return workType;
            }

            @Override
            public ScheduledWorkResult execute(
                    ScheduledWork work,
                    ScheduledWorkContext context) {
                return ScheduledWorkResult.completed();
            }

            @Override
            public void abortRun(ScheduledTaskDefinition task) {
                aborts.add(workType);
                throw failure;
            }
        };
    }

    private static ScheduledWorkExecutor fatalFinalizingExecutor(
            String workType,
            VirtualMachineError failure,
            List<String> events) {
        return new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return workType;
            }

            @Override
            public ScheduledWorkResult execute(
                    ScheduledWork work,
                    ScheduledWorkContext context) {
                return ScheduledWorkResult.completed();
            }

            @Override
            public void finishRun(ScheduledWorkRunContext context) {
                events.add("finish-" + workType);
                throw failure;
            }

            @Override
            public void abortRun(ScheduledTaskDefinition task) {
                events.add("abort-" + workType);
            }
        };
    }

    private static ScheduledTaskStore storeWithCredential() {
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        when(store.listPendingWork(anyLong())).thenReturn(List.of());
        when(store.findCredentialSecret(anyLong(), anyString(), anyString())).thenReturn("fixture-secret");
        return store;
    }

    private record PlanFailureCase(
            ScheduledExecutionPlan plan,
            ScheduledFailure.Category category,
            String code) {
    }

    private static ScheduledSourceExecutor sourceExecutor(int count, Discovery discovery) {
        return sourceExecutor(count, ScheduledPendingReplayPolicy.ALWAYS, discovery);
    }

    private static ScheduledSourceExecutor sourceExecutor(
            int count,
            ScheduledPendingReplayPolicy replayPolicy,
            Discovery discovery) {
        return new ScheduledSourceExecutor() {
            @Override
            public String sourceType() {
                return SOURCE;
            }

            @Override
            public ScheduledExecutionPlan plan(ScheduledTaskDefinition task) {
                return new ScheduledExecutionPlan(
                        Set.of(WORK), POLICY, ScheduledCredentialRequirement.REQUIRED, false,
                        List.of(new ScheduledGuardBinding(
                                GUARD, Set.of(ScheduledGuardPoint.values()), 500)),
                        "fixture.checkpoint", 1, Math.min(count, 8), 0L,
                        ScheduledNetworkRoute.inherit());
            }

            @Override
            public ScheduledPendingReplayPolicy pendingReplayPolicy() {
                return replayPolicy;
            }

            @Override
            public ScheduledDiscoveryResult discover(ScheduledSourceContext context)
                    throws ScheduledExecutionException {
                return discovery.discover(context);
            }
        };
    }

    private static ScheduledWorkExecutor workExecutor(Work work) {
        return new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return WORK;
            }

            @Override
            public ScheduledWorkResult execute(ScheduledWork value, ScheduledWorkContext context)
                    throws ScheduledExecutionException {
                return work.execute(context);
            }
        };
    }

    private static ScheduledCredentialPolicy credentialPolicy(
            AtomicReference<ScheduledNetworkRoute> routeIdentity) {
        return new ScheduledCredentialPolicy() {
            @Override
            public String policyId() {
                return POLICY;
            }

            @Override
            public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context) {
                assertSameRoute(routeIdentity, context.route());
                return ScheduledCredentialProbeResult.valid("account-1");
            }
        };
    }

    private static ScheduledCredentialPolicy bindingPolicy(AtomicInteger probes) {
        return new ScheduledCredentialPolicy() {
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
                probes.incrementAndGet();
                return ScheduledCredentialBindResult.fromProbe(
                        ScheduledCredentialProbeResult.valid("account-1"));
            }
        };
    }

    private static ScheduledExecutionGuard guard(Guard guard) {
        return guard(GUARD, guard);
    }

    private static ScheduledExecutionGuard guard(String guardId, Guard guard) {
        return new ScheduledExecutionGuard() {
            @Override
            public String guardId() {
                return guardId;
            }

            @Override
            public ScheduledGuardResult evaluate(ScheduledGuardContext context)
                    throws ScheduledExecutionException {
                return ScheduledGuardResult.decision(guard.evaluate(context));
            }
        };
    }

    private static ScheduledWork work(String id) {
        return new ScheduledWork(
                new ScheduledWorkKey(WORK, id), "fixture.work", 1, "{}",
                ScheduledWorkPresentation.empty(), List.of());
    }

    private static ScheduledTask task() {
        return taskWithCheckpoint(null, null, null);
    }

    private static ScheduledTask taskWithProxy(String proxySnapshot) {
        return taskWithCheckpoint(null, null, null, proxySnapshot);
    }

    private record CredentialEngineFixture(
            ScheduleExecutionEngine engine,
            String activationToken) {
    }

    private static ScheduledTask taskWithCheckpoint(
            String checkpointSchema,
            Integer checkpointVersion,
            String checkpointJson) {
        return taskWithCheckpoint(
                checkpointSchema, checkpointVersion, checkpointJson, null);
    }

    private static ScheduledTask taskWithCheckpoint(
            String checkpointSchema,
            Integer checkpointVersion,
            String checkpointJson,
            String proxySnapshot) {
        return new ScheduledTask(
                1L, "fixture", true, SOURCE, "fixture",
                "fixture.definition", 1, "{}", "{}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                proxySnapshot, 0L, null, checkpointSchema, checkpointVersion, checkpointJson,
                ScheduledTask.CURRENT_STORAGE_VERSION,
                null, null, ScheduleLastOutcome.NEVER, null, null,
                null, null, null, 0L,
                "fixture", POLICY, "account-1", "{}",
                "fixture-reference", 1L, 1L);
    }

    private static void assertTaskProxy(ScheduledNetworkRoute route) {
        assertThat(route).isNotNull();
        assertThat(route.mode()).isEqualTo(ScheduledNetworkRoute.Mode.PROXY);
        assertThat(route.proxyHost()).isEqualTo("task.proxy");
        assertThat(route.proxyPort()).isEqualTo(9080);
    }

    private static void assertSameRoute(
            AtomicReference<ScheduledNetworkRoute> expected,
            ScheduledNetworkRoute actual) {
        ScheduledNetworkRoute previous = expected.get();
        if (previous == null) {
            expected.compareAndSet(null, actual);
        } else {
            assertThat(actual).isSameAs(previous);
        }
    }

    @FunctionalInterface
    private interface Discovery {
        ScheduledDiscoveryResult discover(ScheduledSourceContext context)
                throws ScheduledExecutionException;
    }

    @FunctionalInterface
    private interface Work {
        ScheduledWorkResult execute(ScheduledWorkContext context)
                throws ScheduledExecutionException;
    }

    @FunctionalInterface
    private interface Guard {
        ScheduledGuardDecision evaluate(ScheduledGuardContext context)
                throws ScheduledExecutionException;
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
        private TestVirtualMachineError(String message) {
            super(message);
        }
    }
}
