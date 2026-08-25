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

@DisplayName("计划能力注册中心租约生命周期")
class ScheduleCapabilityRegistryLeaseTest extends ScheduleCapabilityRegistryTestSupport {

    @Test
    @DisplayName("稳定访问端口保留单能力、planning、复合扩展与 currentness 语义")
    void stableAccessPortPreservesLeaseAndCurrentnessSemantics() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityAccess access = registry;
        ScheduleCapabilityOwner owner =
                owner("stable-feature", "stable-package", 1L);
        Fixture fixture = completeFixture(
                owner,
                "source:stable",
                "SOURCE_STABLE",
                "work:stable",
                "credential:stable",
                "guard:stable");
        ScheduleCapabilityPublication publication = publish(registry, fixture.bundle());

        assertThat(access.snapshot().owners())
                .singleElement()
                .satisfies(snapshot -> {
                    assertThat(snapshot.owner()).isEqualTo(owner);
                    assertThat(snapshot.sourceTypes()).containsExactly("source:stable");
                    assertThat(snapshot.workTypes()).containsExactly("work:stable");
                });
        assertThat(access.credentialPolicyOwner("credential:stable"))
                .contains(owner);

        var ownerLease = access.prepareOwner("stable-feature").orElseThrow();
        try (ownerLease) {
            assertThat(access.activate(ownerLease)).isTrue();
            assertThat(ownerLease.capability()).isEqualTo(owner);
        }

        var policyLease = access.prepareCredentialPolicy("credential:stable").orElseThrow();
        try (policyLease) {
            assertThat(access.activate(policyLease)).isTrue();
            assertThat(policyLease.capability()).isSameAs(fixture.credentialPolicy());
            assertThat(access.whileCurrentPublication(policyLease, () -> "policy-current"))
                    .contains("policy-current");
        }

        var planning = access.prepareSource("SOURCE_STABLE").orElseThrow();
        try (planning) {
            assertThat(access.activate(planning)).isTrue();
            assertThat(access.whileCurrentPublication(planning, () -> "current"))
                    .contains("current");
            var execution = access.prepareExpansion(
                    planning,
                    plan("work:stable", "credential:stable", "guard:stable"))
                    .orElseThrow();
            try (execution) {
                assertThat(access.activate(execution)).isTrue();
                assertThat(execution.workExecutor("work:stable"))
                        .containsSame(fixture.workExecutor());
                assertThat(execution.credentialPolicy())
                        .containsSame(fixture.credentialPolicy());
                assertThat(execution.guard("guard:stable"))
                        .containsSame(fixture.guard());
                assertThat(access.whileCurrentPublication(execution, () -> "execution-current"))
                        .contains("execution-current");
            }
        }

        ScheduleGenerationDrain drain = registry.withdraw(publication).orElseThrow();
        assertThat(drain.isDrained()).isTrue();
        assertThat(access.prepareSource("source:stable")).isEmpty();
        assertThat(access.snapshot().owners()).isEmpty();
    }

    @Test
    @DisplayName("单能力、planning 与复合扩展取得后的致命错误会补偿全部 owner 租约")
    void fatalAfterAcquireOrTransferCompensatesEveryOwnerLease() {
        for (Error expected : new Error[]{new OutOfMemoryError("schedule-fatal"), new ThreadDeath()}) {
            AtomicReference<Error> nextFailure = new AtomicReference<>();
            ScheduleCapabilityRegistry singleRegistry =
                    ScheduleCapabilityRegistryTestAccess.withAcquireProbe(() -> throwPending(nextFailure));
            Fixture singleFixture = completeFixture(
                    owner("fatal-single-feature", "fatal-single-package", 1L),
                    "source:fatal-single", "SOURCE_FATAL_SINGLE",
                    "work:fatal-single", "credential:fatal-single", "guard:fatal-single");
            ScheduleCapabilityPublication singlePublication = publish(singleRegistry, singleFixture.bundle());
            ScheduleCapabilityHandle<ScheduledWorkExecutor> singleHandle =
                    singleRegistry.resolveWorkExecutor("work:fatal-single").orElseThrow();
            ScheduleSingleCapabilityLease<ScheduledWorkExecutor> singleLease =
                    singleRegistry.prepareAcquire(singleHandle).orElseThrow();
            nextFailure.set(expected);
            try (singleLease) {
                assertThat(catchThrowable(() -> singleRegistry.activate(singleLease))).isSameAs(expected);
            }
            assertThat(singleRegistry.withdraw(singlePublication).orElseThrow().activeLeaseCount()).isZero();

            ScheduleCapabilityRegistry transferRegistry =
                    ScheduleCapabilityRegistryTestAccess.withAcquireProbe(() -> throwPending(nextFailure));
            Fixture transferFixture = completeFixture(
                    owner("fatal-transfer-feature", "fatal-transfer-package", 2L),
                    "source:fatal-transfer", "SOURCE_FATAL_TRANSFER",
                    "work:fatal-transfer", "credential:fatal-transfer", "guard:fatal-transfer");
            ScheduleCapabilityPublication transferPublication = publish(transferRegistry, transferFixture.bundle());
            SchedulePlanningLease transferPlanning =
                    transferRegistry.prepareSource("source:fatal-transfer").orElseThrow();
            assertThat(transferRegistry.activate(transferPlanning)).isTrue();
            ScheduleExecutionLease transferExecution = transferRegistry.prepareExpansion(
                    transferPlanning,
                    plan("work:fatal-transfer", "credential:fatal-transfer", "guard:fatal-transfer"))
                    .orElseThrow();
            nextFailure.set(expected);
            try (transferExecution) {
                assertThat(catchThrowable(() -> transferRegistry.activate(transferExecution))).isSameAs(expected);
            }
            assertThat(transferPlanning.isActive()).isFalse();
            transferPlanning.close();
            assertThat(transferRegistry.withdraw(transferPublication).orElseThrow().activeLeaseCount()).isZero();

            ScheduleCapabilityRegistry compositeRegistry =
                    ScheduleCapabilityRegistryTestAccess.withAcquireProbe(() -> throwPending(nextFailure));
            ScheduleCapabilityOwner sourceOwner = owner(
                    "fatal-source-feature", "fatal-source-package", 3L);
            ScheduleCapabilityOwner workOwner = owner(
                    "fatal-work-feature", "fatal-work-package", 4L);
            String sourceType = "source:fatal-composite";
            String workType = "work:fatal-composite";
            ScheduleOwnerBundle sourceBundle = ScheduleOwnerBundle.prepare(
                    sourceOwner,
                    List.of(descriptor(sourceType, Set.of(), workType, Set.of(), Set.of())),
                    List.of(sourceExecutor(sourceType)), List.of(), List.of(), List.of());
            ScheduleCapabilityPublication sourcePublication = publish(compositeRegistry, sourceBundle);
            ScheduleCapabilityPublication workPublication =
                    publish(compositeRegistry, workOnlyBundle(workOwner, workType));
            SchedulePlanningLease compositePlanning =
                    compositeRegistry.prepareSource(sourceType).orElseThrow();
            assertThat(compositeRegistry.activate(compositePlanning)).isTrue();
            ScheduleExecutionLease compositeExecution = compositeRegistry.prepareExpansion(
                    compositePlanning, ScheduledExecutionPlan.credentialFree(Set.of(workType)))
                    .orElseThrow();
            nextFailure.set(expected);
            try (compositeExecution) {
                assertThat(catchThrowable(() -> compositeRegistry.activate(compositeExecution))).isSameAs(expected);
            }
            assertThat(compositePlanning.isActive()).isTrue();
            compositePlanning.close();
            assertThat(compositeRegistry.withdraw(sourcePublication).orElseThrow().activeLeaseCount()).isZero();
            assertThat(compositeRegistry.withdraw(workPublication).orElseThrow().activeLeaseCount()).isZero();
        }
    }

    @Test
    @DisplayName("未激活租约关闭后不可复活且不增加 owner 活动计数")
    void closedPreparedLeasesCannotBeActivated() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityOwner singleOwner = owner("prepared-single", "prepared-single-package", 1L);
        ScheduleCapabilityPublication singlePublication = publish(registry,
                workOnlyBundle(singleOwner, "work:prepared-single"));
        ScheduleCapabilityHandle<ScheduledWorkExecutor> singleHandle =
                registry.resolveWorkExecutor("work:prepared-single").orElseThrow();
        ScheduleSingleCapabilityLease<ScheduledWorkExecutor> single =
                registry.prepareAcquire(singleHandle).orElseThrow();

        assertThat(single.isActive()).isFalse();
        assertThatThrownBy(single::capability).isInstanceOf(IllegalStateException.class);
        single.close();
        assertThat(registry.activate(single)).isFalse();
        assertThat(registry.withdraw(singlePublication).orElseThrow().activeLeaseCount()).isZero();

        ScheduleCapabilityOwner sourceOwner = owner("prepared-source", "prepared-source-package", 2L);
        ScheduleCapabilityOwner workOwner = owner("prepared-work", "prepared-work-package", 3L);
        String sourceType = "source:prepared";
        String workType = "work:prepared";
        ScheduleCapabilityPublication sourcePublication = publish(registry, ScheduleOwnerBundle.prepare(
                sourceOwner,
                List.of(descriptor(sourceType, Set.of(), workType, Set.of(), Set.of())),
                List.of(sourceExecutor(sourceType)), List.of(), List.of(), List.of()));
        ScheduleCapabilityPublication workPublication = publish(registry, workOnlyBundle(workOwner, workType));

        SchedulePlanningLease closedPlanning = registry.prepareSource(sourceType).orElseThrow();
        assertThat(closedPlanning.isActive()).isFalse();
        closedPlanning.close();
        assertThat(registry.activate(closedPlanning)).isFalse();

        SchedulePlanningLease planning = registry.prepareSource(sourceType).orElseThrow();
        assertThat(registry.activate(planning)).isTrue();
        ScheduleExecutionLease execution = registry.prepareExpansion(
                planning, ScheduledExecutionPlan.credentialFree(Set.of(workType))).orElseThrow();
        assertThat(execution.isActive()).isFalse();
        assertThatThrownBy(execution::sourceExecutor).isInstanceOf(IllegalStateException.class);
        execution.close();
        assertThat(registry.activate(execution)).isFalse();
        assertThat(planning.isActive()).isTrue();

        ScheduleGenerationDrain sourceDrain = registry.withdraw(sourcePublication).orElseThrow();
        ScheduleGenerationDrain workDrain = registry.withdraw(workPublication).orElseThrow();
        assertThat(sourceDrain.activeLeaseCount()).isEqualTo(1);
        assertThat(workDrain.activeLeaseCount()).isZero();
        planning.close();
        assertThat(sourceDrain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("根状态关闭后的致命 token 清理失败不会产生假活动计数或可用 Bean")
    void fatalTokenCleanupAfterRootCloseCannotCreateFalseLiveness() {
        for (Error expected : new Error[]{new OutOfMemoryError("schedule-close-fatal"), new ThreadDeath()}) {
            AtomicReference<Error> nextFailure = new AtomicReference<>();
            ScheduleCapabilityRegistry singleRegistry =
                    ScheduleCapabilityRegistryTestAccess.withReleaseProbes(
                            () -> throwPending(nextFailure), () -> {
                            });
            ScheduleCapabilityOwner singleOwner = owner(
                    "close-single-feature", "close-single-package", 1L);
            ScheduleCapabilityPublication singlePublication = publish(singleRegistry,
                    workOnlyBundle(singleOwner, "work:close-single"));
            ScheduleSingleCapabilityLease<ScheduledWorkExecutor> single = singleRegistry.prepareAcquire(
                    singleRegistry.resolveWorkExecutor("work:close-single").orElseThrow()).orElseThrow();
            assertThat(singleRegistry.activate(single)).isTrue();
            ScheduleGenerationDrain singleDrain = singleRegistry.withdraw(singlePublication).orElseThrow();

            nextFailure.set(expected);
            assertThat(catchThrowable(single::close)).isSameAs(expected);
            assertThat(single.isActive()).isFalse();
            assertThatThrownBy(single::capability).isInstanceOf(IllegalStateException.class);
            assertThat(singleDrain.activeLeaseCount()).isZero();

            ScheduleCapabilityRegistry compositeRegistry =
                    ScheduleCapabilityRegistryTestAccess.withReleaseProbes(
                            () -> throwPending(nextFailure), () -> {
                            });
            ScheduleCapabilityOwner sourceOwner = owner(
                    "close-source-feature", "close-source-package", 2L);
            ScheduleCapabilityOwner workOwner = owner(
                    "close-work-feature", "close-work-package", 3L);
            String sourceType = "source:close-composite";
            String workType = "work:close-composite";
            ScheduleCapabilityPublication sourcePublication = publish(compositeRegistry,
                    ScheduleOwnerBundle.prepare(
                            sourceOwner,
                            List.of(descriptor(sourceType, Set.of(), workType, Set.of(), Set.of())),
                            List.of(sourceExecutor(sourceType)), List.of(), List.of(), List.of()));
            ScheduleCapabilityPublication workPublication = publish(compositeRegistry,
                    workOnlyBundle(workOwner, workType));
            SchedulePlanningLease planning = compositeRegistry.prepareSource(sourceType).orElseThrow();
            assertThat(compositeRegistry.activate(planning)).isTrue();
            ScheduleExecutionLease execution = compositeRegistry.prepareExpansion(
                    planning, ScheduledExecutionPlan.credentialFree(Set.of(workType))).orElseThrow();
            assertThat(compositeRegistry.activate(execution)).isTrue();
            ScheduleGenerationDrain sourceDrain = compositeRegistry.withdraw(sourcePublication).orElseThrow();
            ScheduleGenerationDrain workDrain = compositeRegistry.withdraw(workPublication).orElseThrow();

            nextFailure.set(expected);
            assertThat(catchThrowable(execution::close)).isSameAs(expected);
            assertThat(execution.isActive()).isFalse();
            assertThatThrownBy(execution::sourceExecutor).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> execution.workExecutor(workType))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(sourceDrain.activeLeaseCount()).isZero();
            assertThat(workDrain.activeLeaseCount()).isZero();
            planning.close();
        }
    }

    @Test
    @DisplayName("插件进入 STARTED 前计划能力不可取得，启动后才开放租约")
    void lifecycleAdmissionBlocksPlanningBeforeStarted() {
        AtomicBoolean started = new AtomicBoolean();
        ScheduleCapabilityRegistry registry =
                new ScheduleCapabilityRegistry(ignored -> started.get());
        ScheduleCapabilityOwner owner = owner("activation-feature", "activation-package", 1L);
        Fixture fixture = completeFixture(owner, COMPLETE_SOURCE, COMPLETE_ALIAS,
                COMPLETE_WORK, COMPLETE_POLICY, COMPLETE_GUARD);

        publish(registry, fixture.bundle());

        assertThat(registry.prepareSource(COMPLETE_SOURCE)).isEmpty();
        started.set(true);
        SchedulePlanningLease planning = registry.prepareSource(COMPLETE_SOURCE).orElseThrow();
        try (planning) {
            assertThat(registry.activate(planning)).isTrue();
            assertThat(planning.owner()).isEqualTo(owner);
        }
    }

    @Test
    @DisplayName("同 owner 复合执行租约只计一次活动租约")
    void sameOwnerCompositeLeaseIsDeduplicated() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityOwner owner = owner("dedupe-feature", "dedupe-package", 3L);
        Fixture fixture = completeFixture(owner,
                "source:dedupe", "SOURCE_DEDUPE",
                "work:dedupe", "credential:dedupe", "guard:dedupe");
        ScheduleCapabilityPublication publication = publish(registry, fixture.bundle());
        SchedulePlanningLease planning = registry.prepareSource("source:dedupe").orElseThrow();
        assertThat(registry.activate(planning)).isTrue();
        ScheduleExecutionLease execution = registry.prepareExpansion(
                planning, plan("work:dedupe", "credential:dedupe", "guard:dedupe")).orElseThrow();
        assertThat(registry.activate(execution)).isTrue();

        assertThat(planning.isActive()).isFalse();
        assertThat(execution.owners()).containsExactly(owner);
        assertThat(execution.workExecutor("work:dedupe")).containsSame(fixture.workExecutor());
        assertThat(execution.workExecutorOwner("work:dedupe")).contains(owner);
        assertThat(execution.workExecutorOwners()).containsOnly(Map.entry("work:dedupe", owner));
        assertThat(execution.workExecutorPublicationId("work:dedupe"))
                .hasValue(publication.publicationId());
        assertThat(execution.workExecutorPublicationIds())
                .containsOnly(Map.entry("work:dedupe", publication.publicationId()));
        assertThat(execution.credentialPolicy()).containsSame(fixture.credentialPolicy());
        assertThat(execution.credentialPolicyOwner()).contains(owner);
        assertThat(execution.guard("guard:dedupe")).containsSame(fixture.guard());
        assertThat(execution.guardOwner("guard:dedupe")).contains(owner);
        assertThat(execution.guardOwners()).containsOnly(Map.entry("guard:dedupe", owner));

        ScheduleGenerationDrain drain = registry.withdraw(publication).orElseThrow();
        assertThat(drain.activeLeaseCount()).isEqualTo(1);
        assertThat(execution.cancellation().isCancellationRequested()).isTrue();
        execution.close();
        planning.close();
        assertThat(drain.awaitDrained(deadlineAfterMillis(100))).isTrue();
        assertThat(drain.activeLeaseCount()).isZero();
    }

    @Test
    @DisplayName("planning 关闭先赢时复合激活不保留部分 owner 租约")
    void planningCloseWinningActivationRaceLeavesNoPartialOwnerLease() throws Exception {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityOwner sourceOwner = owner("race-source-feature", "race-source-package", 1L);
        ScheduleCapabilityOwner workOwner = owner("race-work-feature", "race-work-package", 2L);
        String sourceType = "source:race";
        String blockerSourceType = "source:race-blocker";
        String workType = "work:race";

        ScheduledSourceDescriptor sourceDescriptor = descriptor(
                sourceType, Set.of(), workType, Set.of(), Set.of());
        ScheduledSourceExecutor raceSourceExecutor = sourceExecutor(sourceType);
        ScheduleOwnerBundle sourceBundle = ScheduleOwnerBundle.prepare(
                sourceOwner, List.of(sourceDescriptor),
                List.of(raceSourceExecutor), List.of(), List.of(), List.of());
        Fixture workFixture = completeFixture(
                workOwner,
                blockerSourceType,
                "SOURCE_RACE_BLOCKER",
                workType,
                "credential:race-blocker",
                "guard:race-blocker");
        ScheduleCapabilityPublication sourcePublication = publish(registry, sourceBundle);
        ScheduleCapabilityPublication workPublication = publish(registry, workFixture.bundle());
        SchedulePlanningLease planning = registry.prepareSource(sourceType).orElseThrow();
        SchedulePlanningLease blockerPlanning = registry.prepareSource(blockerSourceType).orElseThrow();
        assertThat(registry.activate(planning)).isTrue();
        assertThat(registry.activate(blockerPlanning)).isTrue();
        ScheduleLeaseState blockedOwnerState = blockerPlanning.leaseState();
        ScheduleExecutionLease execution = registry.prepareExpansion(
                planning, ScheduledExecutionPlan.credentialFree(Set.of(workType))).orElseThrow();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> expansionThread = new AtomicReference<>();
        try {
            Future<Boolean> activation;
            synchronized (blockedOwnerState) {
                activation = executor.submit(() -> {
                    expansionThread.set(Thread.currentThread());
                    return registry.activate(execution);
                });
                awaitBlockedAt(
                        expansionThread, ScheduleCapabilityRegistry.class, "activate");
                planning.close();
            }

            assertThat(activation.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(execution.isActive()).isFalse();
            assertThatThrownBy(execution::sourceExecutor).isInstanceOf(IllegalStateException.class);
            assertThat(planning.isActive()).isFalse();

            ScheduleGenerationDrain sourceDrain = registry.withdraw(sourcePublication).orElseThrow();
            ScheduleGenerationDrain workDrain = registry.withdraw(workPublication).orElseThrow();
            assertThat(sourceDrain.activeLeaseCount()).isZero();
            assertThat(workDrain.activeLeaseCount()).isEqualTo(1);
            execution.close();
            blockerPlanning.close();
            assertThat(sourceDrain.awaitDrained(deadlineAfterMillis(100))).isTrue();
            assertThat(workDrain.awaitDrained(deadlineAfterMillis(100))).isTrue();
        } finally {
            execution.close();
            planning.close();
            blockerPlanning.close();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("跨 owner 复合租约中任一 owner 撤回都会触发共享取消")
    void withdrawingAnyCompositeOwnerRequestsSharedCancellation() {
        for (CompositeOwner target : CompositeOwner.values()) {
            ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
            ScheduleCapabilityOwner sourceOwner = owner("source-feature", "source-package", 1L);
            ScheduleCapabilityOwner workOwner = owner("work-feature", "work-package", 2L);
            ScheduleCapabilityOwner policyOwner = owner("policy-feature", "policy-package", 3L);
            ScheduleCapabilityOwner guardOwner = owner("guard-feature", "guard-package", 4L);

            ScheduledSourceDescriptor descriptor = descriptor(
                    "source:composite", Set.of("SOURCE_COMPOSITE"), "work:composite",
                    Set.of("credential:composite"), Set.of("guard:composite"));
            ScheduleOwnerBundle sourceBundle = ScheduleOwnerBundle.prepare(
                    sourceOwner, List.of(descriptor),
                    List.of(sourceExecutor("source:composite")), List.of(), List.of(), List.of());
            ScheduleOwnerBundle workBundle = workOnlyBundle(workOwner, "work:composite");
            ScheduleOwnerBundle policyBundle = policyOnlyBundle(policyOwner, "credential:composite");
            ScheduleOwnerBundle guardBundle = guardOnlyBundle(guardOwner, "guard:composite");

            Map<CompositeOwner, ScheduleCapabilityPublication> publications =
                    new EnumMap<>(CompositeOwner.class);
            publications.put(CompositeOwner.SOURCE, publish(registry, sourceBundle));
            publications.put(CompositeOwner.WORK, publish(registry, workBundle));
            publications.put(CompositeOwner.POLICY, publish(registry, policyBundle));
            publications.put(CompositeOwner.GUARD, publish(registry, guardBundle));

            SchedulePlanningLease planning = registry.prepareSource("source:composite").orElseThrow();
            assertThat(registry.activate(planning)).isTrue();
            ScheduleExecutionLease execution = registry.prepareExpansion(
                    planning, plan("work:composite", "credential:composite", "guard:composite"))
                    .orElseThrow();
            assertThat(registry.activate(execution)).isTrue();
            ScheduledCancellation cancellation = execution.cancellation();
            assertThat(execution.owners()).containsExactlyInAnyOrder(
                    sourceOwner, workOwner, policyOwner, guardOwner);
            ScheduleCapabilityOwner resolvedWorkOwner =
                    execution.workExecutorOwner("work:composite").orElseThrow();
            assertThat(resolvedWorkOwner).isEqualTo(workOwner);
            assertThat(resolvedWorkOwner.featurePluginId()).isEqualTo("work-feature");
            assertThat(resolvedWorkOwner.packageId()).isEqualTo("work-package");
            assertThat(resolvedWorkOwner.pluginGeneration()).isEqualTo(2L);
            assertThat(execution.workExecutorOwners())
                    .containsOnly(Map.entry("work:composite", workOwner));
            assertThat(execution.workExecutorPublicationId("work:composite"))
                    .hasValue(publications.get(CompositeOwner.WORK).publicationId());
            assertThat(execution.workExecutorPublicationIds())
                    .containsOnly(Map.entry(
                            "work:composite",
                            publications.get(CompositeOwner.WORK).publicationId()));
            ScheduleCapabilityOwner resolvedPolicyOwner =
                    execution.credentialPolicyOwner().orElseThrow();
            assertThat(resolvedPolicyOwner).isEqualTo(policyOwner);
            assertThat(resolvedPolicyOwner.featurePluginId()).isEqualTo("policy-feature");
            assertThat(resolvedPolicyOwner.packageId()).isEqualTo("policy-package");
            assertThat(resolvedPolicyOwner.pluginGeneration()).isEqualTo(3L);
            ScheduleCapabilityOwner resolvedGuardOwner =
                    execution.guardOwner("guard:composite").orElseThrow();
            assertThat(resolvedGuardOwner).isEqualTo(guardOwner);
            assertThat(resolvedGuardOwner.featurePluginId()).isEqualTo("guard-feature");
            assertThat(resolvedGuardOwner.packageId()).isEqualTo("guard-package");
            assertThat(resolvedGuardOwner.pluginGeneration()).isEqualTo(4L);
            assertThat(execution.guardOwners())
                    .containsOnly(Map.entry("guard:composite", guardOwner));
            assertThat(cancellation.isCancellationRequested()).as(target.name()).isFalse();

            ScheduleGenerationDrain drain = registry.withdraw(publications.get(target)).orElseThrow();
            assertThat(cancellation.isCancellationRequested()).as(target.name()).isTrue();
            assertThat(drain.activeLeaseCount()).as(target.name()).isEqualTo(1);
            AtomicBoolean operationCalled = new AtomicBoolean();
            assertThat(registry.whileCurrentPublication(execution, () -> {
                operationCalled.set(true);
                return "written";
            })).as(target.name()).isEmpty();
            assertThat(operationCalled).as(target.name()).isFalse();

            execution.close();
            planning.close();
            assertThat(drain.awaitDrained(deadlineAfterMillis(100))).as(target.name()).isTrue();
        }
    }

    @Test
    @DisplayName("撤回后拒绝新租约且 drain 必须等待既有租约关闭")
    void withdrawalRejectsNewLeasesAndDrainWaitsForClose() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        Fixture fixture = completeFixture(
                owner("drain-feature", "drain-package", 5L),
                "source:drain", "SOURCE_DRAIN",
                "work:drain", "credential:drain", "guard:drain");
        ScheduleCapabilityPublication publication = publish(registry, fixture.bundle());
        ScheduleCapabilityHandle<ScheduledWorkExecutor> handle =
                registry.resolveWorkExecutor("work:drain").orElseThrow();
        ScheduleSingleCapabilityLease<ScheduledWorkExecutor> lease =
                registry.prepareAcquire(handle).orElseThrow();
        assertThat(registry.activate(lease)).isTrue();
        ScheduledCancellation cancellation = lease.cancellation();

        ScheduleGenerationDrain drain = registry.withdraw(publication).orElseThrow();
        assertThat(cancellation.isCancellationRequested()).isTrue();
        assertThat(registry.prepareAcquire(handle)).isEmpty();
        assertThat(registry.prepareSource("source:drain")).isEmpty();
        assertThat(registry.resolveWorkExecutor("work:drain")).isEmpty();
        assertThat(drain.awaitDrained(deadlineAfterMillis(10))).isFalse();
        assertThat(drain.activeLeaseCount()).isEqualTo(1);

        lease.close();
        assertThat(drain.awaitDrained(deadlineAfterMillis(100))).isTrue();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("执行租约与单能力租约关闭后拒绝行为访问并完成 drain")
    void closedExecutionAndSingleLeasesRejectCapabilityAccessAndDrain() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        Fixture executionFixture = completeFixture(
                owner("execution-feature", "execution-package", 6L),
                "source:execution", "SOURCE_EXECUTION",
                "work:execution", "credential:execution", "guard:execution");
        ScheduleCapabilityPublication executionPublication = publish(registry, executionFixture.bundle());
        SchedulePlanningLease planning = registry.prepareSource("source:execution").orElseThrow();
        assertThat(registry.activate(planning)).isTrue();
        ScheduleExecutionLease execution = registry.prepareExpansion(
                planning, plan("work:execution", "credential:execution", "guard:execution"))
                .orElseThrow();
        assertThat(registry.activate(execution)).isTrue();
        ScheduleGenerationDrain executionDrain = registry.withdraw(executionPublication).orElseThrow();

        execution.close();
        assertThat(execution.isActive()).isFalse();
        assertThatThrownBy(() -> execution.descriptor()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.sourceExecutor()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.workExecutor("work:execution"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.workExecutorOwner("work:execution"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.workExecutorOwners())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.credentialPolicy()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.credentialPolicyOwner())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.guard("guard:execution"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.guardOwner("guard:execution"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.guardOwners())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.cancellation()).isInstanceOf(IllegalStateException.class);
        assertThat(executionDrain.awaitDrained(deadlineAfterMillis(100))).isTrue();

        ScheduleCapabilityOwner singleOwner = owner("single-feature", "single-package", 7L);
        ScheduleOwnerBundle singleBundle = workOnlyBundle(singleOwner, "work:single");
        ScheduleCapabilityPublication singlePublication = publish(registry, singleBundle);
        ScheduleCapabilityHandle<ScheduledWorkExecutor> singleHandle =
                registry.resolveWorkExecutor("work:single").orElseThrow();
        ScheduleSingleCapabilityLease<ScheduledWorkExecutor> single =
                registry.prepareAcquire(singleHandle).orElseThrow();
        assertThat(registry.activate(single)).isTrue();
        ScheduleGenerationDrain singleDrain = registry.withdraw(singlePublication).orElseThrow();

        single.close();
        assertThat(single.isActive()).isFalse();
        assertThatThrownBy(() -> single.capability()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> single.cancellation()).isInstanceOf(IllegalStateException.class);
        assertThat(singleDrain.awaitDrained(deadlineAfterMillis(100))).isTrue();
    }

    @Test
    @DisplayName("并发读者只能看到空快照或来源与执行能力完整的一代")
    void concurrentReadersNeverObserveSourceOnlyGeneration() throws Exception {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean writerDone = new AtomicBoolean();
        AtomicInteger observedPublishedSnapshots = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            futures.add(executor.submit(() -> {
                await(start);
                try {
                    for (int generation = 1; generation <= 100; generation++) {
                        ScheduleCapabilityOwner owner = owner(
                                "concurrent-feature", "concurrent-package", generation);
                        Fixture fixture = completeFixture(owner,
                                "source:concurrent", "SOURCE_CONCURRENT",
                                "work:concurrent", "credential:concurrent", "guard:concurrent");
                        ScheduleCapabilityPublication publication = publish(registry, fixture.bundle());
                        LockSupport.parkNanos(200_000L);
                        registry.withdraw(publication).orElseThrow();
                    }
                } finally {
                    writerDone.set(true);
                }
            }));

            for (int reader = 0; reader < 3; reader++) {
                futures.add(executor.submit(() -> {
                    await(start);
                    while (!writerDone.get()) {
                        assertCompleteConcurrentSnapshot(
                                registry.snapshotView(), observedPublishedSnapshots);
                        Thread.onSpinWait();
                    }
                    assertCompleteConcurrentSnapshot(
                            registry.snapshotView(), observedPublishedSnapshots);
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(observedPublishedSnapshots.get()).isPositive();
        assertThat(registry.snapshotView().owners()).isEmpty();
        assertThat(registry.snapshotView().revision()).isEqualTo(200L);
    }

}
