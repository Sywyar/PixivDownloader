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

@DisplayName("计划执行引擎共享夹具")
abstract class ScheduleExecutionEngineTestSupport {

    protected static final String SOURCE = "fixture-source";
    protected static final String WORK = "fixture-work";
    protected static final String POLICY = "fixture-policy";
    protected static final String GUARD = "fixture-guard";

    protected static ScheduleExecutionEngine engine(
            ScheduledTaskStore store,
            ScheduledSourceExecutor source,
            ScheduledWorkExecutor work,
            ScheduledCredentialPolicy policy,
            ScheduledExecutionGuard guard) throws Exception {
        return credentialEngine(store, source, work, policy, guard).engine();
    }

    protected static CredentialEngineFixture credentialEngine(
            ScheduledTaskStore store,
            ScheduledSourceExecutor source,
            ScheduledWorkExecutor work,
            ScheduledCredentialPolicy policy,
            ScheduledExecutionGuard guard) throws Exception {
        return credentialEngine(store, source, work, policy, guard,
                new ScheduleRunState(), new SyncTaskExecutor());
    }

    protected static ScheduleExecutionEngine engine(
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

    protected static CredentialEngineFixture credentialEngine(
            ScheduledTaskStore store,
            ScheduledSourceExecutor source,
            ScheduledWorkExecutor work,
            ScheduledCredentialPolicy policy,
            ScheduledExecutionGuard guard,
            ScheduleRunState runState,
            TaskExecutor taskExecutor) throws Exception {
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        ScheduleCapabilityOwner owner = new ScheduleCapabilityOwner("fixture", "fixture-package", 1L);
        publish(registry, bindingBundle(owner, source, work, policy, guard));
        return new CredentialEngineFixture(
                engine(store, registry, runState, taskExecutor),
                activationToken(registry));
    }

    protected static void publish(
            FakeScheduleCapabilityAccess registry,
            ScheduleCapabilityTestFixture.CapabilityBundle bundle) {
        ScheduleCapabilityTestFixture.publish(registry, bundle);
    }

    protected static ScheduleCapabilityTestFixture.CapabilityBundle bindingBundle(
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
        return ScheduleCapabilityTestFixture.bundle(
                owner, List.of(descriptor), List.of(source),
                List.of(work), List.of(policy), List.of(guard));
    }

    protected static String activationToken(ScheduleCapabilityAccess registry) {
        return registry.snapshot().owners().stream()
                .filter(owner -> owner.owner().featurePluginId().equals("fixture"))
                .findFirst()
                .orElseThrow()
                .activationToken();
    }

    protected static ScheduleExecutionEngine engine(
            ScheduledTaskStore store,
            ScheduleCapabilityAccess registry,
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

    protected static ScheduledWorkExecutor finalizingExecutor(
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

    protected static ScheduledExecutionPlan plan(
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

    protected static ScheduledSourceExecutor sourceWithPlan(
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

    protected static FakeScheduleCapabilityAccess.Publication publishExecutionFixture(
            FakeScheduleCapabilityAccess registry,
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
        return ScheduleCapabilityTestFixture.publish(
                registry,
                ScheduleCapabilityTestFixture.bundle(
                        new ScheduleCapabilityOwner("fixture", "fixture-package", 1L),
                        List.of(descriptor),
                        List.of(source),
                        workExecutors,
                        List.of(credentialPolicy(new AtomicReference<>())),
                        guards));
    }

    protected static ScheduledWorkExecutor abortingExecutor(
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

    protected static ScheduledWorkExecutor fatalFinalizingExecutor(
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

    protected static ScheduledTaskStore storeWithCredential() {
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        when(store.listPendingWork(anyLong())).thenReturn(List.of());
        when(store.findCredentialSecret(anyLong(), anyString(), anyString())).thenReturn("fixture-secret");
        return store;
    }

    protected record PlanFailureCase(
            ScheduledExecutionPlan plan,
            ScheduledFailure.Category category,
            String code) {
    }

    protected record BindingEchoCase(
            String field,
            ScheduledCredentialBindResult result,
            String expectedCode) {
    }

    protected record ProbeEchoCase(
            String field,
            ScheduledCredentialProbeResult result) {
    }

    protected record StoredCredentialEchoCase(
            ScheduledTask task,
            String expectedCode) {
    }

    protected static ScheduledSourceExecutor sourceExecutor(int count, Discovery discovery) {
        return sourceExecutor(count, ScheduledPendingReplayPolicy.ALWAYS, discovery);
    }

    protected static ScheduledSourceExecutor sourceExecutor(
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

    protected static ScheduledWorkExecutor workExecutor(Work work) {
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

    protected static ScheduledCredentialPolicy credentialPolicy(
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

    protected static ScheduledCredentialPolicy bindingPolicy(AtomicInteger probes) {
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

    protected static ScheduledExecutionGuard guard(Guard guard) {
        return guard(GUARD, guard);
    }

    protected static ScheduledExecutionGuard guard(String guardId, Guard guard) {
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

    protected static ScheduledWork work(String id) {
        return new ScheduledWork(
                new ScheduledWorkKey(WORK, id), "fixture.work", 1, "{}",
                ScheduledWorkPresentation.empty(), List.of());
    }

    protected static ScheduledTask task() {
        return taskWithCheckpoint(null, null, null);
    }

    protected static ScheduledTask taskWithProxy(String proxySnapshot) {
        return taskWithCheckpoint(null, null, null, proxySnapshot);
    }

    protected record CredentialEngineFixture(
            ScheduleExecutionEngine engine,
            String activationToken) {
    }

    protected static ScheduledTask taskWithCheckpoint(
            String checkpointSchema,
            Integer checkpointVersion,
            String checkpointJson) {
        return taskWithCheckpoint(
                checkpointSchema, checkpointVersion, checkpointJson, null);
    }

    protected static ScheduledTask taskWithCheckpoint(
            String checkpointSchema,
            Integer checkpointVersion,
            String checkpointJson,
            String proxySnapshot) {
        return taskWithStoredArtifacts(
                checkpointSchema, checkpointVersion, checkpointJson, proxySnapshot, "{}");
    }

    protected static ScheduledTask taskWithStoredArtifacts(
            String checkpointSchema,
            Integer checkpointVersion,
            String checkpointJson,
            String credentialPolicyStateJson) {
        return taskWithStoredArtifacts(
                checkpointSchema, checkpointVersion, checkpointJson, null,
                credentialPolicyStateJson);
    }

    protected static ScheduledTask taskWithStoredArtifacts(
            String checkpointSchema,
            Integer checkpointVersion,
            String checkpointJson,
            String proxySnapshot,
            String credentialPolicyStateJson) {
        return new ScheduledTask(
                1L, "fixture", true, SOURCE, "fixture",
                "fixture.definition", 1, "{}", "{}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                proxySnapshot, 0L, null, checkpointSchema, checkpointVersion, checkpointJson,
                ScheduledTask.CURRENT_STORAGE_VERSION,
                null, null, ScheduleLastOutcome.NEVER, null, null,
                null, null, null, 0L,
                "fixture", POLICY, "account-1", credentialPolicyStateJson,
                "fixture-reference", 1L);
    }

    protected static void assertTaskProxy(ScheduledNetworkRoute route) {
        assertThat(route).isNotNull();
        assertThat(route.mode()).isEqualTo(ScheduledNetworkRoute.Mode.PROXY);
        assertThat(route.proxyHost()).isEqualTo("task.proxy");
        assertThat(route.proxyPort()).isEqualTo(9080);
    }

    protected static void assertSameRoute(
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
    protected interface Discovery {
        ScheduledDiscoveryResult discover(ScheduledSourceContext context)
                throws ScheduledExecutionException;
    }

    @FunctionalInterface
    protected interface Work {
        ScheduledWorkResult execute(ScheduledWorkContext context)
                throws ScheduledExecutionException;
    }

    @FunctionalInterface
    protected interface Guard {
        ScheduledGuardDecision evaluate(ScheduledGuardContext context)
                throws ScheduledExecutionException;
    }

    protected static final class TestVirtualMachineError extends VirtualMachineError {
        protected TestVirtualMachineError(String message) {
            super(message);
        }
    }
}
