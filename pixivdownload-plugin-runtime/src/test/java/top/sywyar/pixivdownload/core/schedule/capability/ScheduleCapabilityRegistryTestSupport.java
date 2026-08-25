package top.sywyar.pixivdownload.core.schedule.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.schedule.migration.LegacySchedulePersistenceDescriptor;
import top.sywyar.pixivdownload.core.schedule.migration.LegacySchedulePersistenceDescriptorProvider;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialContext;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardContext;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardResult;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;

import java.util.ArrayList;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
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
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("计划能力注册中心共享夹具")
abstract class ScheduleCapabilityRegistryTestSupport {

    protected static final String COMPLETE_SOURCE = "source:complete";
    protected static final String COMPLETE_ALIAS = "SOURCE_COMPLETE";
    protected static final String COMPLETE_WORK = "work:complete";
    protected static final String COMPLETE_POLICY = "credential:complete";
    protected static final String COMPLETE_GUARD = "guard:complete";

    protected static void assertCompleteConcurrentSnapshot(
            ScheduleCapabilityRegistry.SnapshotView snapshot,
            AtomicInteger observedPublishedSnapshots) {
        assertThat(snapshot.owners()).hasSizeLessThanOrEqualTo(1);
        for (ScheduleCapabilityRegistry.OwnerView view : snapshot.owners()) {
            observedPublishedSnapshots.incrementAndGet();
            assertThat(view.sourceTypes()).containsExactly("source:concurrent");
            assertThat(view.sourceAliases()).containsExactly("SOURCE_CONCURRENT");
            assertThat(view.workTypes()).containsExactly("work:concurrent");
            assertThat(view.credentialPolicyIds()).containsExactly("credential:concurrent");
            assertThat(view.guardIds()).containsExactly("guard:concurrent");
            assertThat(view.sourceDescriptors()).singleElement().satisfies(descriptor -> {
                assertThat(descriptor.sourceType()).isEqualTo("source:concurrent");
                assertThat(descriptor.possibleWorkTypes()).containsExactly("work:concurrent");
                assertThat(descriptor.credentialPolicyIds()).containsExactly("credential:concurrent");
                assertThat(descriptor.guardIds()).containsExactly("guard:concurrent");
            });
        }
    }

    protected static Fixture completeFixture(
            ScheduleCapabilityOwner owner,
            String sourceType,
            String alias,
            String workType,
            String policyId,
            String guardId) {
        ScheduledSourceDescriptor descriptor = descriptor(
                sourceType, Set.of(alias), workType, Set.of(policyId), Set.of(guardId));
        ScheduledSourceExecutor sourceExecutor = sourceExecutor(sourceType);
        ScheduledWorkExecutor workExecutor = workExecutor(workType);
        ScheduledCredentialPolicy credentialPolicy = credentialPolicy(policyId);
        ScheduledExecutionGuard guard = guard(guardId);
        ScheduleOwnerBundle bundle = ScheduleOwnerBundle.prepare(
                owner,
                List.of(descriptor),
                List.of(sourceExecutor),
                List.of(workExecutor),
                List.of(credentialPolicy),
                List.of(guard));
        return new Fixture(bundle, descriptor, sourceExecutor,
                workExecutor, credentialPolicy, guard);
    }

    protected static ScheduleOwnerBundle workOnlyBundle(
            ScheduleCapabilityOwner owner, String workType) {
        return ScheduleOwnerBundle.prepare(
                owner, List.of(), List.of(),
                List.of(workExecutor(workType)), List.of(), List.of());
    }

    protected static ScheduleOwnerBundle policyOnlyBundle(
            ScheduleCapabilityOwner owner, String policyId) {
        return ScheduleOwnerBundle.prepare(
                owner, List.of(), List.of(), List.of(),
                List.of(credentialPolicy(policyId)), List.of());
    }

    protected static ScheduleOwnerBundle legacyMigrationBundle(
            ScheduleCapabilityOwner owner,
            String sourceType,
            String alias,
            String policyId) {
        LegacySchedulePersistenceDescriptorProvider persistence = () -> List.of(
                new LegacySchedulePersistenceDescriptor(
                        sourceType, sourceType + ":definition", 1,
                        Set.of("work:legacy"), Set.of(policyId)));
        ScheduledSourceDescriptor descriptor = descriptor(
                sourceType, Set.of(alias), "work:legacy", Set.of(policyId), Set.of());
        return ScheduleOwnerBundle.prepare(
                owner, List.of(descriptor), List.of(sourceExecutor(sourceType)),
                List.of(workExecutor("work:legacy")), List.of(), List.of(), List.of(persistence));
    }

    protected static ScheduleOwnerBundle guardOnlyBundle(
            ScheduleCapabilityOwner owner, String guardId) {
        return ScheduleOwnerBundle.prepare(
                owner, List.of(), List.of(), List.of(), List.of(),
                List.of(guard(guardId)));
    }

    protected static ScheduledSourceDescriptor descriptor(
            String sourceType,
            Set<String> aliases,
            String workType,
            Set<String> policyIds,
            Set<String> guardIds) {
        return new ScheduledSourceDescriptor(
                sourceType,
                aliases,
                sourceType + ":definition",
                1,
                new ScheduledSourcePresentation(
                        "schedule-test", "schedule.source.name", "schedule.source.description",
                        "schedule", "neutral"),
                Set.of("default"),
                Set.of(workType),
                policyIds,
                guardIds,
                null);
    }

    protected static ScheduledSourceExecutor sourceExecutor(String sourceType) {
        return new ScheduledSourceExecutor() {
            @Override
            public String sourceType() {
                return sourceType;
            }

            @Override
            public ScheduledExecutionPlan plan(ScheduledTaskDefinition task) {
                return ScheduledExecutionPlan.credentialFree(Set.of("unused"));
            }

            @Override
            public ScheduledDiscoveryResult discover(ScheduledSourceContext context) {
                return ScheduledDiscoveryResult.withoutCheckpoint();
            }
        };
    }

    protected static ScheduledWorkExecutor workExecutor(String workType) {
        return new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return workType;
            }

            @Override
            public ScheduledWorkResult execute(
                    top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork work,
                    ScheduledWorkContext context) {
                return ScheduledWorkResult.completed();
            }
        };
    }

    protected static ScheduledCredentialPolicy credentialPolicy(String policyId) {
        return new ScheduledCredentialPolicy() {
            @Override
            public String policyId() {
                return policyId;
            }

            @Override
            public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context) {
                return ScheduledCredentialProbeResult.valid("test-account");
            }
        };
    }

    protected static ScheduledExecutionGuard guard(String guardId) {
        return new ScheduledExecutionGuard() {
            @Override
            public String guardId() {
                return guardId;
            }

            @Override
            public ScheduledGuardResult evaluate(ScheduledGuardContext context) {
                return ScheduledGuardResult.decision(ScheduledGuardDecision.proceed());
            }
        };
    }

    protected static ScheduledExecutionPlan plan(String workType, String policyId, String guardId) {
        return new ScheduledExecutionPlan(
                Set.of(workType),
                policyId,
                ScheduledCredentialRequirement.REQUIRED,
                false,
                List.of(new ScheduledGuardBinding(
                        guardId, Set.of(ScheduledGuardPoint.RUN_START), 0)),
                null,
                0,
                1,
                0L,
                ScheduledNetworkRoute.inherit());
    }

    protected static ScheduleCapabilityOwner owner(
            String featurePluginId, String packageId, long generation) {
        return new ScheduleCapabilityOwner(featurePluginId, packageId, generation);
    }

    protected static long deadlineAfterMillis(long millis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    }

    protected static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test worker interrupted", interrupted);
        }
    }

    protected static void throwPending(AtomicReference<Error> pending) {
        Error failure = pending.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
    }

    protected static ScheduleCapabilityPublication publish(
            ScheduleCapabilityRegistry registry,
            ScheduleOwnerBundle bundle) {
        return ScheduleCapabilityRegistryTestAccess.publish(registry, bundle);
    }

    protected static void awaitBlockedAt(
            AtomicReference<Thread> threadReference,
            Class<?> declaringClass,
            String methodName) {
        long deadline = deadlineAfterMillis(5_000);
        while (System.nanoTime() < deadline) {
            Thread thread = threadReference.get();
            if (thread != null && thread.getState() == Thread.State.BLOCKED) {
                for (StackTraceElement frame : thread.getStackTrace()) {
                    if (frame.getClassName().equals(declaringClass.getName())
                            && frame.getMethodName().equals(methodName)) {
                        return;
                    }
                }
            }
            LockSupport.parkNanos(100_000L);
        }
        Thread thread = threadReference.get();
        throw new AssertionError("线程未在预期监视器处阻塞: "
                + declaringClass.getName() + "#" + methodName
                + ", state=" + (thread == null ? "not-started" : thread.getState()));
    }

    protected enum CompositeOwner {
        SOURCE,
        WORK,
        POLICY,
        GUARD
    }

    protected record ConflictCase(String label, Fixture fixture) {
    }

    protected record Fixture(
            ScheduleOwnerBundle bundle,
            ScheduledSourceDescriptor descriptor,
            ScheduledSourceExecutor sourceExecutor,
            ScheduledWorkExecutor workExecutor,
            ScheduledCredentialPolicy credentialPolicy,
            ScheduledExecutionGuard guard
    ) {
    }
}
