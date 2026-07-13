package top.sywyar.pixivdownload.core.schedule.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.schedule.migration.LegacySchedulePersistenceDescriptor;
import top.sywyar.pixivdownload.core.schedule.migration.LegacySchedulePersistenceDescriptorProvider;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkSettings;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
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
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleState;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;

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

@DisplayName("计划任务统一能力注册中心")
class ScheduleCapabilityRegistryTest {

    private static final String COMPLETE_SOURCE = "source:complete";
    private static final String COMPLETE_ALIAS = "SOURCE_COMPLETE";
    private static final String COMPLETE_WORK = "work:complete";
    private static final String COMPLETE_POLICY = "credential:complete";
    private static final String COMPLETE_GUARD = "guard:complete";

    @Test
    @DisplayName("单能力、planning 与复合扩展取得后的致命错误会补偿全部 owner 租约")
    void fatalAfterAcquireOrTransferCompensatesEveryOwnerLease() {
        for (Error expected : new Error[]{new OutOfMemoryError("schedule-fatal"), new ThreadDeath()}) {
            AtomicReference<Error> nextFailure = new AtomicReference<>();
            ScheduleCapabilityRegistry singleRegistry =
                    ScheduleCapabilityRegistryTestAccess.withAcquireProbe(() -> throwPending(nextFailure));
            Fixture singleFixture = completeFixture(
                    owner("fatal-single-feature", "fatal-single-package", 1L),
                    "source:fatal-single", "source:fatal-single", "SOURCE_FATAL_SINGLE",
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
                    "source:fatal-transfer", "source:fatal-transfer", "SOURCE_FATAL_TRANSFER",
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
                    sourceOwner, List.of(), List.of(),
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
                sourceOwner, List.of(), List.of(),
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
                            sourceOwner, List.of(), List.of(),
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
        PluginLifecycleState lifecycle = new PluginLifecycleState();
        lifecycle.initialize("activation-feature", PluginRuntimePhase.LOADED);
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry(lifecycle);
        ScheduleCapabilityOwner owner = owner("activation-feature", "activation-package", 1L);
        Fixture fixture = completeFixture(owner, COMPLETE_SOURCE, COMPLETE_SOURCE, COMPLETE_ALIAS,
                COMPLETE_WORK, COMPLETE_POLICY, COMPLETE_GUARD);

        publish(registry, fixture.bundle());

        assertThat(registry.prepareSource(COMPLETE_SOURCE)).isEmpty();
        lifecycle.transition("activation-feature", PluginRuntimePhase.STARTED);
        SchedulePlanningLease planning = registry.prepareSource(COMPLETE_SOURCE).orElseThrow();
        try (planning) {
            assertThat(registry.activate(planning)).isTrue();
            assertThat(planning.owner()).isEqualTo(owner);
        }
    }

    @Test
    @DisplayName("单个 owner 的完整能力通过一次快照原子可见")
    void publishesCompleteOwnerThroughOneAtomicSnapshot() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityOwner owner = owner("complete-feature", "complete-package", 7L);
        Fixture fixture = completeFixture(owner, COMPLETE_SOURCE, COMPLETE_SOURCE, COMPLETE_ALIAS,
                COMPLETE_WORK, COMPLETE_POLICY, COMPLETE_GUARD);

        ScheduleCapabilityRegistry.SnapshotView before = registry.snapshotView();
        ScheduleCapabilityPublication publication = publish(registry, fixture.bundle());
        ScheduleCapabilityRegistry.SnapshotView after = registry.snapshotView();

        assertThat(before.revision()).isZero();
        assertThat(before.owners()).isEmpty();
        assertThat(after).isNotSameAs(before);
        assertThat(after.revision()).isEqualTo(1L);
        assertThat(after.owners()).singleElement().satisfies(view -> {
            assertThat(view.owner()).isEqualTo(owner);
            assertThat(view.publicationId()).isEqualTo(publication.publicationId());
            assertThat(view.legacySourceTypes()).containsExactly(COMPLETE_SOURCE);
            assertThat(view.sourceTypes()).containsExactly(COMPLETE_SOURCE);
            assertThat(view.sourceAliases()).containsExactly(COMPLETE_ALIAS);
            assertThat(view.workTypes()).containsExactly(COMPLETE_WORK);
            assertThat(view.credentialPolicyIds()).containsExactly(COMPLETE_POLICY);
            assertThat(view.guardIds()).containsExactly(COMPLETE_GUARD);
            assertThat(view.sourceDescriptors()).singleElement().isSameAs(fixture.descriptor());
        });

        assertThat(registry.resolveSourceDescriptor(COMPLETE_SOURCE)).isPresent();
        assertThat(registry.resolveSourceExecutor(COMPLETE_ALIAS)).isPresent();
        assertThat(registry.resolveLegacySource(COMPLETE_ALIAS)).isPresent();
        assertThat(registry.resolveWorkExecutor(COMPLETE_WORK)).isPresent();
        assertThat(registry.resolveLegacyWorkRunner(COMPLETE_WORK)).isPresent();
        assertThat(registry.resolveCredentialPolicy(COMPLETE_POLICY)).isPresent();
        assertThat(registry.resolveGuard(COMPLETE_GUARD)).isPresent();

        SchedulePlanningLease lease = registry.prepareSource(COMPLETE_ALIAS).orElseThrow();
        try (lease) {
            assertThat(registry.activate(lease)).isTrue();
            assertThat(lease.descriptor()).containsSame(fixture.descriptor());
            assertThat(lease.sourceExecutor()).containsSame(fixture.sourceExecutor());
            assertThat(lease.legacySourceProvider()).containsSame(fixture.legacySource());
        }
    }

    @Test
    @DisplayName("同代重新发布会轮换来源激活令牌且保持进程 epoch")
    void sameGenerationRepublicationRotatesActivationTokenWithinEpoch() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityOwner owner = owner("activation-feature", "activation-package", 4L);
        Fixture fixture = completeFixture(
                owner,
                "source:activation",
                "source:activation",
                "SOURCE_ACTIVATION",
                "work:activation",
                "credential:activation",
                "guard:activation");

        String epoch = registry.snapshotView().epoch();
        ScheduleCapabilityPublication first = publish(registry, fixture.bundle());
        String firstToken = registry.snapshotView().owners().get(0).activationToken();
        SchedulePlanningLease planning = registry.prepareSource("source:activation").orElseThrow();
        try (planning) {
            assertThat(registry.activate(planning)).isTrue();
            assertThat(planning.activationToken()).isEqualTo(firstToken);
        }

        registry.withdraw(first).orElseThrow();
        publish(registry, fixture.bundle());
        ScheduleCapabilityRegistry.SnapshotView current = registry.snapshotView();

        assertThat(current.epoch()).isEqualTo(epoch).isNotBlank();
        assertThat(current.owners()).singleElement().satisfies(view ->
                assertThat(view.activationToken()).isNotEqualTo(firstToken).isNotBlank());
    }

    @Test
    @DisplayName("已先撤回的 publication 不进入宿主持久化 barrier")
    void retiredPublicationSkipsCurrentPublicationOperation() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        Fixture fixture = completeFixture(
                owner("barrier-feature", "barrier-package", 1L),
                "source:barrier",
                "source:barrier",
                "SOURCE_BARRIER",
                "work:barrier",
                "credential:barrier",
                "guard:barrier");
        ScheduleCapabilityPublication publication = publish(registry, fixture.bundle());
        SchedulePlanningLease planning = registry.prepareSource("source:barrier").orElseThrow();
        assertThat(registry.activate(planning)).isTrue();
        ScheduleGenerationDrain drain = registry.withdraw(publication).orElseThrow();
        AtomicBoolean operationCalled = new AtomicBoolean();

        assertThat(registry.whileCurrentPublication(planning, () -> {
            operationCalled.set(true);
            return "written";
        })).isEmpty();
        assertThat(operationCalled).isFalse();
        assertThat(drain.isDrained()).isFalse();

        planning.close();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("publication 等值伪造不能撤回真实发布，mutation 与构造入口均非 public")
    void publicationIdentityAndMutationVisibilityAreHostInternal() throws Exception {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityOwner owner = owner("identity-feature", "identity-package", 1L);
        Fixture fixture = completeFixture(owner, "identity-source", "identity-source", "IDENTITY_SOURCE",
                "identity-work", "identity-policy", "identity-guard");
        ScheduleCapabilityPublication actual = publish(registry, fixture.bundle());
        ScheduleCapabilityPublication forged =
                ScheduleCapabilityRegistryTestAccess.equivalent(actual);

        assertThat(forged).isNotSameAs(actual);
        assertThat(forged.owner()).isEqualTo(actual.owner());
        assertThat(forged.publicationId()).isEqualTo(actual.publicationId());
        assertThat(registry.withdraw(forged)).isEmpty();
        assertThat(registry.publication(owner)).containsSame(actual);
        assertThat(registry.withdraw(actual)).isPresent();

        assertThat(ScheduleCapabilityPublication.class.getDeclaredConstructors())
                .allMatch(constructor -> !Modifier.isPublic(constructor.getModifiers()));
        assertThat(List.of(
                "allocateReservation", "reserve", "commit", "release",
                "withdraw", "rollback", "acknowledgeRetired",
                "forgetRetirementAcknowledgement", "publication"))
                .allSatisfy(name -> assertThat(java.util.Arrays.stream(
                        ScheduleCapabilityRegistry.class.getDeclaredMethods())
                        .filter(method -> method.getName().equals(name)))
                        .isNotEmpty()
                        .allMatch(method -> !Modifier.isPublic(method.getModifiers())));
    }

    @Test
    @DisplayName("来源描述符缺少执行器时准备失败且快照与 revision 均不污染")
    void missingSourceExecutorDoesNotPolluteSnapshotOrRevision() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        Fixture existing = completeFixture(
                owner("existing-feature", "existing-package", 1L),
                "source:existing", "source:existing", "SOURCE_EXISTING",
                "work:existing", "credential:existing", "guard:existing");
        publish(registry, existing.bundle());
        ScheduleCapabilityRegistry.SnapshotView before = registry.snapshotView();

        ScheduleCapabilityOwner rejectedOwner = owner("missing-feature", "missing-package", 2L);
        ScheduledSourceDescriptor descriptor = descriptor(
                "source:missing", Set.of("SOURCE_MISSING"),
                "work:missing", Set.of(), Set.of());

        assertThatThrownBy(() -> ScheduleOwnerBundle.prepare(
                rejectedOwner,
                List.of(),
                List.of(),
                List.of(descriptor),
                List.of(),
                List.of(workExecutor("work:missing")),
                List.of(),
                List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing executors")
                .hasMessageContaining("source:missing");

        assertThat(registry.snapshotView()).isSameAs(before);
        assertThat(registry.snapshotView().revision()).isEqualTo(1L);
        assertThat(registry.publication(rejectedOwner)).isEmpty();
        assertThat(registry.resolveSourceDescriptor("source:existing")).isPresent();
    }

    @Test
    @DisplayName("跨 owner 的来源别名、作品、凭证策略与 Guard 冲突均失败且不污染快照")
    void crossOwnerConflictsLeavePublishedSnapshotUntouched() {
        Fixture existing = completeFixture(
                owner("base-feature", "base-package", 1L),
                "source:base", "source:base", "SHARED_SOURCE_ALIAS",
                "work:shared", "credential:shared", "guard:shared");
        List<ConflictCase> conflicts = List.of(
                new ConflictCase("来源别名", completeFixture(
                        owner("alias-feature", "alias-package", 2L),
                        "source:alias", "source:alias", "SHARED_SOURCE_ALIAS",
                        "work:alias", "credential:alias", "guard:alias")),
                new ConflictCase("作品", completeFixture(
                        owner("work-feature", "work-package", 3L),
                        "source:work", "source:work", "SOURCE_WORK",
                        "work:shared", "credential:work", "guard:work")),
                new ConflictCase("凭证策略", completeFixture(
                        owner("policy-feature", "policy-package", 4L),
                        "source:policy", "source:policy", "SOURCE_POLICY",
                        "work:policy", "credential:shared", "guard:policy")),
                new ConflictCase("Guard", completeFixture(
                        owner("guard-feature", "guard-package", 5L),
                        "source:guard", "source:guard", "SOURCE_GUARD",
                        "work:guard", "credential:guard", "guard:shared"))
        );

        for (ConflictCase conflict : conflicts) {
            ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
            publish(registry, existing.bundle());
            ScheduleCapabilityRegistry.SnapshotView before = registry.snapshotView();

            assertThatThrownBy(() -> publish(registry, conflict.fixture().bundle()))
                    .as(conflict.label())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("duplicate");

            assertThat(registry.snapshotView()).as(conflict.label()).isSameAs(before);
            assertThat(registry.snapshotView().revision()).as(conflict.label()).isEqualTo(1L);
            assertThat(registry.publication(conflict.fixture().bundle().owner())).isEmpty();
            assertThat(registry.resolveSourceDescriptor("source:base")).isPresent();
            assertThat(registry.resolveWorkExecutor("work:shared")).isPresent();
            assertThat(registry.resolveCredentialPolicy("credential:shared")).isPresent();
            assertThat(registry.resolveGuard("guard:shared")).isPresent();
        }
    }

    @Test
    @DisplayName("同一 owner 的新旧不同 canonical 可共享别名并在一个来源租约中同时解析")
    void newAndLegacyDifferentCanonicalsMayShareAliasForSameOwner() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityOwner owner = owner("dual-feature", "dual-package", 9L);
        Fixture fixture = completeFixture(owner,
                "source:new-canonical", "source:legacy-canonical", "SHARED_LEGACY_ALIAS",
                "work:dual", "credential:dual", "guard:dual");
        publish(registry, fixture.bundle());

        assertThat(registry.resolveSourceDescriptor("source:new-canonical")).isPresent();
        assertThat(registry.resolveLegacySource("source:legacy-canonical")).isPresent();
        assertThat(registry.resolveSourceDescriptor("SHARED_LEGACY_ALIAS")).isPresent();
        assertThat(registry.resolveLegacySource("SHARED_LEGACY_ALIAS")).isPresent();

        SchedulePlanningLease lease = registry.prepareSource("SHARED_LEGACY_ALIAS").orElseThrow();
        try (lease) {
            assertThat(registry.activate(lease)).isTrue();
            assertThat(lease.owner()).isEqualTo(owner);
            assertThat(lease.sourceType()).isEqualTo("source:new-canonical");
            assertThat(lease.descriptor()).containsSame(fixture.descriptor());
            assertThat(lease.sourceExecutor()).containsSame(fixture.sourceExecutor());
            assertThat(lease.legacySourceProvider()).containsSame(fixture.legacySource());
        }
    }

    @Test
    @DisplayName("其它未提交 reservation 中的凭证策略不能授权旧 secret 迁移")
    void uncommittedCredentialPolicyReservationCannotStampMigrationRoute() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleOwnerBundle policyBundle = policyOnlyBundle(
                owner("policy-feature", "policy-package", 1L), "credential:reserved");
        ScheduleCapabilityReservation policyReservation =
                registry.allocateReservation(policyBundle.owner());
        registry.reserve(policyReservation, policyBundle);
        ScheduleOwnerBundle sourceBundle = legacyMigrationBundle(
                owner("source-feature", "source-package", 2L),
                "source:legacy", "SOURCE_LEGACY", "credential:reserved");
        ScheduleCapabilityReservation sourceReservation =
                registry.allocateReservation(sourceBundle.owner());
        registry.reserve(sourceReservation, sourceBundle);

        assertThatThrownBy(() -> registry.reservedMigrationSnapshot(sourceReservation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable credential policy credential:reserved");

        assertThat(registry.release(sourceReservation)).isTrue();
        assertThat(registry.release(policyReservation)).isTrue();
        assertThat(registry.snapshotView().owners()).isEmpty();
    }

    @Test
    @DisplayName("句柄携带宿主 owner、package、generation 与 publication 且撤回后保持失效")
    void handleCarriesStampedOwnerAndBecomesStaleAfterWithdrawal() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityOwner owner = owner("stamp-feature", "stamp-package", 42L);
        Fixture fixture = completeFixture(owner,
                "source:stamp", "source:stamp", "SOURCE_STAMP",
                "work:stamp", "credential:stamp", "guard:stamp");
        ScheduleCapabilityPublication first = publish(registry, fixture.bundle());
        ScheduleCapabilityHandle<ScheduledWorkExecutor> stale =
                registry.resolveWorkExecutor("work:stamp").orElseThrow();
        ScheduleCapabilityHandle<ScheduleCapabilityOwner> staleOwner =
                registry.resolveOwner("stamp-feature").orElseThrow();
        ScheduleSingleCapabilityLease<ScheduleCapabilityOwner> ownerLease =
                registry.prepareAcquire(staleOwner).orElseThrow();
        assertThat(registry.activate(ownerLease)).isTrue();

        assertThat(stale.owner()).isEqualTo(owner);
        assertThat(stale.owner().featurePluginId()).isEqualTo("stamp-feature");
        assertThat(stale.owner().packageId()).isEqualTo("stamp-package");
        assertThat(stale.owner().pluginGeneration()).isEqualTo(42L);
        assertThat(stale.publicationId()).isEqualTo(first.publicationId());
        assertThat(stale.capabilityId()).isEqualTo("work:stamp");
        assertThat(staleOwner.owner()).isEqualTo(owner);
        assertThat(staleOwner.publicationId()).isEqualTo(first.publicationId());
        assertThat(staleOwner.capabilityId()).isEqualTo("stamp-feature");
        assertThat(ownerLease.capability()).isEqualTo(owner);

        ScheduleGenerationDrain firstDrain = registry.withdraw(first).orElseThrow();
        assertThat(ownerLease.cancellation().isCancellationRequested()).isTrue();
        assertThat(firstDrain.activeLeaseCount()).isEqualTo(1);
        assertThat(firstDrain.isDrained()).isFalse();
        assertThat(registry.prepareAcquire(stale)).isEmpty();
        assertThat(registry.prepareAcquire(staleOwner)).isEmpty();
        assertThat(registry.resolveOwner("stamp-feature")).isEmpty();
        assertThat(registry.resolveWorkExecutor("work:stamp")).isEmpty();
        ownerLease.close();
        assertThat(firstDrain.awaitDrained(deadlineAfterMillis(100))).isTrue();

        ScheduleCapabilityPublication second = publish(registry, fixture.bundle());
        ScheduleCapabilityHandle<ScheduledWorkExecutor> current =
                registry.resolveWorkExecutor("work:stamp").orElseThrow();
        assertThat(second.publicationId()).isGreaterThan(first.publicationId());
        assertThat(current.publicationId()).isEqualTo(second.publicationId());
        assertThat(current).isNotEqualTo(stale);
        assertThat(registry.prepareAcquire(stale)).isEmpty();
        ScheduleSingleCapabilityLease<ScheduledWorkExecutor> lease =
                registry.prepareAcquire(current).orElseThrow();
        try (lease) {
            assertThat(registry.activate(lease)).isTrue();
            assertThat(lease.capability()).isSameAs(fixture.workExecutor());
        }
    }

    @Test
    @DisplayName("同 owner 复合执行租约只计一次活动租约")
    void sameOwnerCompositeLeaseIsDeduplicated() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityOwner owner = owner("dedupe-feature", "dedupe-package", 3L);
        Fixture fixture = completeFixture(owner,
                "source:dedupe", "source:dedupe", "SOURCE_DEDUPE",
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
                sourceOwner, List.of(), List.of(), List.of(sourceDescriptor),
                List.of(raceSourceExecutor), List.of(), List.of(), List.of());
        Fixture workFixture = completeFixture(
                workOwner,
                blockerSourceType,
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
                    sourceOwner, List.of(), List.of(), List.of(descriptor),
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
                "source:drain", "source:drain", "SOURCE_DRAIN",
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
                "source:execution", "source:execution", "SOURCE_EXECUTION",
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
                                "source:concurrent", "source:concurrent", "SOURCE_CONCURRENT",
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

    private static void assertCompleteConcurrentSnapshot(
            ScheduleCapabilityRegistry.SnapshotView snapshot,
            AtomicInteger observedPublishedSnapshots) {
        assertThat(snapshot.owners()).hasSizeLessThanOrEqualTo(1);
        for (ScheduleCapabilityRegistry.OwnerView view : snapshot.owners()) {
            observedPublishedSnapshots.incrementAndGet();
            assertThat(view.legacySourceTypes()).containsExactly("source:concurrent");
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

    private static Fixture completeFixture(
            ScheduleCapabilityOwner owner,
            String newSourceType,
            String legacySourceType,
            String alias,
            String workType,
            String policyId,
            String guardId) {
        ScheduledSourceDescriptor descriptor = descriptor(
                newSourceType, Set.of(alias), workType, Set.of(policyId), Set.of(guardId));
        ScheduledSourceExecutor sourceExecutor = sourceExecutor(newSourceType);
        ScheduledSourceProvider legacySource = legacySource(legacySourceType, Set.of(alias));
        ScheduledWorkExecutor workExecutor = workExecutor(workType);
        ScheduledWorkRunner legacyWorkRunner = legacyWorkRunner(workType);
        ScheduledCredentialPolicy credentialPolicy = credentialPolicy(policyId);
        ScheduledExecutionGuard guard = guard(guardId);
        ScheduleOwnerBundle bundle = ScheduleOwnerBundle.prepare(
                owner,
                List.of(legacySource),
                List.of(legacyWorkRunner),
                List.of(descriptor),
                List.of(sourceExecutor),
                List.of(workExecutor),
                List.of(credentialPolicy),
                List.of(guard));
        return new Fixture(bundle, descriptor, sourceExecutor, legacySource,
                workExecutor, legacyWorkRunner, credentialPolicy, guard);
    }

    private static ScheduleOwnerBundle workOnlyBundle(
            ScheduleCapabilityOwner owner, String workType) {
        return ScheduleOwnerBundle.prepare(
                owner, List.of(), List.of(), List.of(), List.of(),
                List.of(workExecutor(workType)), List.of(), List.of());
    }

    private static ScheduleOwnerBundle policyOnlyBundle(
            ScheduleCapabilityOwner owner, String policyId) {
        return ScheduleOwnerBundle.prepare(
                owner, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(credentialPolicy(policyId)), List.of());
    }

    private static ScheduleOwnerBundle legacyMigrationBundle(
            ScheduleCapabilityOwner owner,
            String sourceType,
            String alias,
            String policyId) {
        LegacySchedulePersistenceDescriptorProvider persistence = () -> List.of(
                new LegacySchedulePersistenceDescriptor(
                        sourceType, sourceType + ":definition", 1,
                        Set.of("work:legacy"), Set.of(policyId)));
        return ScheduleOwnerBundle.prepare(
                owner, List.of(legacySource(sourceType, Set.of(alias))), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(persistence));
    }

    private static ScheduleOwnerBundle guardOnlyBundle(
            ScheduleCapabilityOwner owner, String guardId) {
        return ScheduleOwnerBundle.prepare(
                owner, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(guard(guardId)));
    }

    private static ScheduledSourceDescriptor descriptor(
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

    private static ScheduledSourceExecutor sourceExecutor(String sourceType) {
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

    private static ScheduledSourceProvider legacySource(String sourceType, Set<String> aliases) {
        return new ScheduledSourceProvider() {
            @Override
            public String type() {
                return sourceType;
            }

            @Override
            public Set<String> legacyTypeNames() {
                return aliases;
            }
        };
    }

    private static ScheduledWorkExecutor workExecutor(String workType) {
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

    private static ScheduledWorkRunner legacyWorkRunner(String workType) {
        return new ScheduledWorkRunner() {
            @Override
            public String kind() {
                return workType;
            }

            @Override
            public boolean download(ScheduledWork work, ScheduledWorkSettings settings, String cookie) {
                return true;
            }
        };
    }

    private static ScheduledCredentialPolicy credentialPolicy(String policyId) {
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

    private static ScheduledExecutionGuard guard(String guardId) {
        return new ScheduledExecutionGuard() {
            @Override
            public String guardId() {
                return guardId;
            }

            @Override
            public ScheduledGuardDecision evaluate(ScheduledGuardContext context) {
                return ScheduledGuardDecision.proceed();
            }
        };
    }

    private static ScheduledExecutionPlan plan(String workType, String policyId, String guardId) {
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
                0L);
    }

    private static ScheduleCapabilityOwner owner(
            String featurePluginId, String packageId, long generation) {
        return new ScheduleCapabilityOwner(featurePluginId, packageId, generation);
    }

    private static long deadlineAfterMillis(long millis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test worker interrupted", interrupted);
        }
    }

    private static void throwPending(AtomicReference<Error> pending) {
        Error failure = pending.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
    }

    private static ScheduleCapabilityPublication publish(
            ScheduleCapabilityRegistry registry,
            ScheduleOwnerBundle bundle) {
        return ScheduleCapabilityRegistryTestAccess.publish(registry, bundle);
    }

    private static void awaitBlockedAt(
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

    private enum CompositeOwner {
        SOURCE,
        WORK,
        POLICY,
        GUARD
    }

    private record ConflictCase(String label, Fixture fixture) {
    }

    private record Fixture(
            ScheduleOwnerBundle bundle,
            ScheduledSourceDescriptor descriptor,
            ScheduledSourceExecutor sourceExecutor,
            ScheduledSourceProvider legacySource,
            ScheduledWorkExecutor workExecutor,
            ScheduledWorkRunner legacyWorkRunner,
            ScheduledCredentialPolicy credentialPolicy,
            ScheduledExecutionGuard guard
    ) {
    }
}
