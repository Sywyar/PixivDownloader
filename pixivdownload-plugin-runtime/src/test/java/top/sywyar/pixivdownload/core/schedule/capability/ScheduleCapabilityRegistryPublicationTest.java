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

@DisplayName("计划能力注册中心 publication 与持久化屏障")
class ScheduleCapabilityRegistryPublicationTest extends ScheduleCapabilityRegistryTestSupport {

    @Test
    @DisplayName("单个 owner 的完整能力通过一次快照原子可见")
    void publishesCompleteOwnerThroughOneAtomicSnapshot() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityOwner owner = owner("complete-feature", "complete-package", 7L);
        Fixture fixture = completeFixture(owner, COMPLETE_SOURCE, COMPLETE_ALIAS,
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
            assertThat(view.sourceTypes()).containsExactly(COMPLETE_SOURCE);
            assertThat(view.sourceAliases()).containsExactly(COMPLETE_ALIAS);
            assertThat(view.workTypes()).containsExactly(COMPLETE_WORK);
            assertThat(view.credentialPolicyIds()).containsExactly(COMPLETE_POLICY);
            assertThat(view.guardIds()).containsExactly(COMPLETE_GUARD);
            assertThat(view.sourceDescriptors()).singleElement().isSameAs(fixture.descriptor());
        });

        assertThat(registry.resolveSourceDescriptor(COMPLETE_SOURCE)).isPresent();
        assertThat(registry.resolveSourceExecutor(COMPLETE_ALIAS)).isPresent();
        assertThat(registry.resolveWorkExecutor(COMPLETE_WORK)).isPresent();
        assertThat(registry.resolveCredentialPolicy(COMPLETE_POLICY)).isPresent();
        assertThat(registry.resolveGuard(COMPLETE_GUARD)).isPresent();

        SchedulePlanningLease lease = registry.prepareSource(COMPLETE_ALIAS).orElseThrow();
        try (lease) {
            assertThat(registry.activate(lease)).isTrue();
            assertThat(lease.descriptor()).containsSame(fixture.descriptor());
            assertThat(lease.sourceExecutor()).containsSame(fixture.sourceExecutor());
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
    @DisplayName("已撤回或被替换的凭证策略 publication 不进入单项宿主写入 barrier")
    void retiredCredentialPolicySkipsSingleCurrentPublicationOperation() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityOwner owner = owner("policy-feature", "policy-package", 1L);
        String policyId = "credential:policy-currentness";
        ScheduleCapabilityPublication first = publish(
                registry, policyOnlyBundle(owner, policyId));
        var lease = registry.prepareCredentialPolicy(policyId).orElseThrow();
        assertThat(registry.activate(lease)).isTrue();
        ScheduleGenerationDrain drain = registry.withdraw(first).orElseThrow();
        publish(registry, policyOnlyBundle(owner, policyId));
        AtomicBoolean operationCalled = new AtomicBoolean();

        assertThat(registry.whileCurrentPublication(lease, () -> {
            operationCalled.set(true);
            return "written";
        })).isEmpty();
        assertThat(operationCalled).isFalse();
        assertThat(drain.isDrained()).isFalse();

        lease.close();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("已撤回的复合 owner publication 不进入执行期宿主写入 barrier")
    void retiredCompositeOwnerSkipsExecutionCurrentPublicationOperation() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        Fixture fixture = completeFixture(
                owner("execution-barrier-feature", "execution-barrier-package", 1L),
                "source:execution-barrier",
                "SOURCE_EXECUTION_BARRIER",
                "work:execution-barrier",
                "credential:execution-barrier",
                "guard:execution-barrier");
        ScheduleCapabilityPublication publication = publish(registry, fixture.bundle());
        SchedulePlanningLease planning = registry.prepareSource(
                "source:execution-barrier").orElseThrow();
        assertThat(registry.activate(planning)).isTrue();
        ScheduleExecutionLease execution = registry.prepareExpansion(
                planning,
                plan("work:execution-barrier", "credential:execution-barrier",
                        "guard:execution-barrier"))
                .orElseThrow();
        assertThat(registry.activate(execution)).isTrue();
        ScheduleGenerationDrain drain = registry.withdraw(publication).orElseThrow();
        AtomicBoolean operationCalled = new AtomicBoolean();

        assertThat(registry.whileCurrentPublication(execution, () -> {
            operationCalled.set(true);
            return "written";
        })).isEmpty();
        assertThat(operationCalled).isFalse();
        assertThat(drain.isDrained()).isFalse();

        execution.close();
        planning.close();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("publication 等值伪造不能撤回真实发布，mutation 与构造入口均非 public")
    void publicationIdentityAndMutationVisibilityAreHostInternal() throws Exception {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityOwner owner = owner("identity-feature", "identity-package", 1L);
        Fixture fixture = completeFixture(owner, "identity-source", "IDENTITY_SOURCE",
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
    @DisplayName("句柄携带宿主 owner、package、generation 与 publication 且撤回后保持失效")
    void handleCarriesStampedOwnerAndBecomesStaleAfterWithdrawal() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityOwner owner = owner("stamp-feature", "stamp-package", 42L);
        Fixture fixture = completeFixture(owner,
                "source:stamp", "SOURCE_STAMP",
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
            assertThat(lease.publicationId()).isEqualTo(second.publicationId());
            assertThat(registry.activate(lease)).isTrue();
            assertThat(lease.capability()).isSameAs(fixture.workExecutor());
        }
    }

}
