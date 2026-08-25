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

@DisplayName("计划能力注册中心 bundle 校验")
class ScheduleCapabilityRegistryValidationTest extends ScheduleCapabilityRegistryTestSupport {

    @Test
    @DisplayName("来源描述符缺少执行器时准备失败且快照与 revision 均不污染")
    void missingSourceExecutorDoesNotPolluteSnapshotOrRevision() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        Fixture existing = completeFixture(
                owner("existing-feature", "existing-package", 1L),
                "source:existing", "SOURCE_EXISTING",
                "work:existing", "credential:existing", "guard:existing");
        publish(registry, existing.bundle());
        ScheduleCapabilityRegistry.SnapshotView before = registry.snapshotView();

        ScheduleCapabilityOwner rejectedOwner = owner("missing-feature", "missing-package", 2L);
        ScheduledSourceDescriptor descriptor = descriptor(
                "source:missing", Set.of("SOURCE_MISSING"),
                "work:missing", Set.of(), Set.of());

        assertThatThrownBy(() -> ScheduleOwnerBundle.prepare(
                rejectedOwner,
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
                "source:base", "SHARED_SOURCE_ALIAS",
                "work:shared", "credential:shared", "guard:shared");
        List<ConflictCase> conflicts = List.of(
                new ConflictCase("来源别名", completeFixture(
                        owner("alias-feature", "alias-package", 2L),
                        "source:alias", "SHARED_SOURCE_ALIAS",
                        "work:alias", "credential:alias", "guard:alias")),
                new ConflictCase("作品", completeFixture(
                        owner("work-feature", "work-package", 3L),
                        "source:work", "SOURCE_WORK",
                        "work:shared", "credential:work", "guard:work")),
                new ConflictCase("凭证策略", completeFixture(
                        owner("policy-feature", "policy-package", 4L),
                        "source:policy", "SOURCE_POLICY",
                        "work:policy", "credential:shared", "guard:policy")),
                new ConflictCase("Guard", completeFixture(
                        owner("guard-feature", "guard-package", 5L),
                        "source:guard", "SOURCE_GUARD",
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
    @DisplayName("描述符旧别名与 canonical 解析到同一当前来源")
    void descriptorLegacyAliasResolvesCurrentCanonicalSource() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityOwner owner = owner("alias-feature", "alias-package", 9L);
        Fixture fixture = completeFixture(owner,
                "source:canonical", "SOURCE_LEGACY_ALIAS",
                "work:alias", "credential:alias", "guard:alias");
        publish(registry, fixture.bundle());

        assertThat(registry.resolveSourceDescriptor("source:canonical")).isPresent();
        assertThat(registry.resolveSourceDescriptor("SOURCE_LEGACY_ALIAS")).isPresent();
        assertThat(registry.resolveSourceExecutor("SOURCE_LEGACY_ALIAS")).isPresent();

        SchedulePlanningLease lease = registry.prepareSource("SOURCE_LEGACY_ALIAS").orElseThrow();
        try (lease) {
            assertThat(registry.activate(lease)).isTrue();
            assertThat(lease.owner()).isEqualTo(owner);
            assertThat(lease.sourceType()).isEqualTo("source:canonical");
            assertThat(lease.descriptor()).containsSame(fixture.descriptor());
            assertThat(lease.sourceExecutor()).containsSame(fixture.sourceExecutor());
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

}
