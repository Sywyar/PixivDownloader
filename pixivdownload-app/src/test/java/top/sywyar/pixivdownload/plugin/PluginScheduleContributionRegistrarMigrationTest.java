package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.migration.LegacySchedulePersistenceDescriptor;
import top.sywyar.pixivdownload.core.schedule.migration.LegacySchedulePersistenceDescriptorProvider;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledCredentialPolicyTarget;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationAdapter;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationResult;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationRoute;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationService;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.core.schedule.capability.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.ScheduleContributionLifecycleAuthority;
import top.sywyar.pixivdownload.plugin.lifecycle.ScheduleContributionLifecycleAuthorityTestAccess;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 统一 schedule owner bundle 的准备、发布与精确撤回回归。 */
@DisplayName("外置计划能力注册器旧任务迁移")
class PluginScheduleContributionRegistrarMigrationTest extends PluginScheduleContributionRegistrarTestSupport {

    @Test
    @DisplayName("迁移必须先用宿主 alias 路由完成任务隔离再发布 owner，且不缓存报告与插件引用")
    void migratesFromPreparedBundleBeforePublication() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        AtomicReference<String> capturedOwner = new AtomicReference<>();
        AtomicReference<Map<String, LegacyScheduledTaskMigrationRoute>> capturedRoutes =
                new AtomicReference<>();
        AtomicReference<LegacyScheduledTaskMigrationAdapter> capturedAdapter = new AtomicReference<>();
        LegacyScheduledTaskMigrationService migrationService = (reservation, adapter) -> {
            var reserved = registry.reservedMigrationSnapshot(reservation);
            assertThat(registry.snapshotView().owners()).isEmpty();
            assertThatThrownBy(() -> ScheduleCapabilityRegistryTestAccess.publish(
                    registry, ownerBundle(
                            new ScheduleCapabilityOwner("ext-b", "other-package", 1L),
                            List.of(sourceDescriptor("beta", "beta-work", "ALPHA")))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("duplicate scheduled source alias");
            assertThat(registry.snapshotView().owners()).isEmpty();
            capturedOwner.set(reserved.ownerPluginId());
            capturedRoutes.set(reserved.routes());
            capturedAdapter.set(adapter);
            return new LegacyScheduledTaskMigrationService.OwnerMigrationReport(
                    reserved.ownerPluginId(), 1, 0, 0, 1);
        };
        PluginScheduleContributionRegistrar registrar = registrar(registry, migrationService);
        LegacyScheduledTaskMigrationAdapter adapter = snapshot ->
                new LegacyScheduledTaskMigrationResult.Rejected("NOT_USED", "{}");

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            ScheduledSourceDescriptor descriptor = sourceDescriptor("alpha", "alpha-work", "ALPHA");
            registerCompleteBeans(child, List.of(descriptor));
            child.registerBean("legacy-migration-adapter", LegacyScheduledTaskMigrationAdapter.class, () -> adapter);
            child.registerBean("legacy-persistence", LegacySchedulePersistenceDescriptorProvider.class,
                    () -> () -> List.of(new LegacySchedulePersistenceDescriptor(
                            "alpha", "alpha.definition", 1, Set.of("alpha-work"),
                            descriptor.credentialPolicyIds())));
            child.refresh();

            assertThat(register(registrar, registeredFeature(
                    "ext-a", "ext-a", 9L,
                    List.of(descriptor)), child)).isPresent();
        }

        assertThat(capturedOwner).hasValue("ext-a");
        assertThat(capturedRoutes.get()).containsOnlyKeys("ALPHA");
        assertThat(capturedRoutes.get().get("ALPHA")).isEqualTo(
                LegacyScheduledTaskMigrationRoute.descriptorBound(
                        "alpha", "alpha.definition", 1, Set.of("alpha-work"),
                        Set.of(new LegacyScheduledCredentialPolicyTarget("alpha-policy", "ext-a"))));
        assertThat(capturedAdapter).hasValue(adapter);
        assertThat(registry.resolveSourceDescriptor("ALPHA")).isPresent();
        assertThat(Arrays.stream(PluginScheduleContributionRegistrar.class.getDeclaredFields()))
                .noneMatch(field -> LegacyScheduledTaskMigrationAdapter.class.isAssignableFrom(field.getType())
                        || LegacyScheduledTaskMigrationService.OwnerMigrationReport.class
                        .isAssignableFrom(field.getType())
                        || Throwable.class.isAssignableFrom(field.getType()));
    }

    @Test
    @DisplayName("迁移 route 从已发布的外部凭证策略盖章真实 owner")
    void stampsPublishedExternalCredentialPolicyOwnerIntoMigrationRoute() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityRegistryTestAccess.publish(registry, ownerBundle(
                new ScheduleCapabilityOwner("credential-owner", "credential-package", 1L),
                List.of(sourceDescriptor("credential-source", "credential-work")),
                List.of(credentialPolicy("shared-policy"))));
        AtomicReference<Map<String, LegacyScheduledTaskMigrationRoute>> capturedRoutes =
                new AtomicReference<>();
        LegacyScheduledTaskMigrationService migrationService = (reservation, adapter) -> {
            var reserved = registry.reservedMigrationSnapshot(reservation);
            capturedRoutes.set(reserved.routes());
            return new LegacyScheduledTaskMigrationService.OwnerMigrationReport(
                    reserved.ownerPluginId(), 0, 0, 0, 0);
        };
        PluginScheduleContributionRegistrar registrar = registrar(registry, migrationService);

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            ScheduledSourceDescriptor descriptor = sourceDescriptor(
                    "alpha", "alpha-work", Set.of("shared-policy"), Set.of(), "ALPHA");
            child.registerBean("source-executor", ScheduledSourceExecutor.class,
                    () -> sourceExecutor("alpha"));
            child.registerBean("work-executor", ScheduledWorkExecutor.class,
                    () -> workExecutor("alpha-work"));
            child.registerBean("legacy-adapter", LegacyScheduledTaskMigrationAdapter.class,
                    () -> snapshot -> new LegacyScheduledTaskMigrationResult.Rejected("NOT_USED", "{}"));
            child.registerBean("legacy-persistence", LegacySchedulePersistenceDescriptorProvider.class,
                    () -> () -> List.of(new LegacySchedulePersistenceDescriptor(
                            "alpha", "alpha.definition", 1, Set.of("alpha-work"),
                            Set.of("shared-policy"))));
            child.refresh();

            assertThat(register(registrar, registeredFeature(
                    "source-owner", "source-owner", 2L,
                    List.of(descriptor)), child)).isPresent();
        }

        LegacyScheduledTaskMigrationRoute route = capturedRoutes.get().get("ALPHA");
        assertThat(route.credentialPolicyTarget("shared-policy"))
                .contains(new LegacyScheduledCredentialPolicyTarget(
                        "shared-policy", "credential-owner"));
    }

    @Test
    @DisplayName("旧任务声明的凭证策略未发布时在调用迁移前拒绝 reservation")
    void rejectsUnavailableCredentialPolicyBeforeMigration() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        AtomicBoolean migrationCalled = new AtomicBoolean();
        LegacyScheduledTaskMigrationService migrationService = (reservation, adapter) -> {
            migrationCalled.set(true);
            return new LegacyScheduledTaskMigrationService.OwnerMigrationReport(
                    registry.reservedMigrationSnapshot(reservation).ownerPluginId(), 0, 0, 0, 0);
        };
        PluginScheduleContributionRegistrar registrar = registrar(registry, migrationService);

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            ScheduledSourceDescriptor descriptor = sourceDescriptor(
                    "alpha", "alpha-work", Set.of("missing-policy"), Set.of(), "ALPHA");
            child.registerBean("source-executor", ScheduledSourceExecutor.class,
                    () -> sourceExecutor("alpha"));
            child.registerBean("work-executor", ScheduledWorkExecutor.class,
                    () -> workExecutor("alpha-work"));
            child.registerBean("legacy-persistence", LegacySchedulePersistenceDescriptorProvider.class,
                    () -> () -> List.of(new LegacySchedulePersistenceDescriptor(
                            "alpha", "alpha.definition", 1, Set.of("alpha-work"),
                            Set.of("missing-policy"))));
            child.refresh();

            assertThatThrownBy(() -> register(registrar, registeredFeature(
                    "source-owner", "source-owner", 3L,
                    List.of(descriptor)), child))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unavailable credential policy missing-policy");
        }

        assertThat(migrationCalled).isFalse();
        assertThat(registry.snapshotView().owners()).isEmpty();
    }

    @Test
    @DisplayName("registry 预留应在迁移前拒绝跨 canonical 重复 alias")
    void rejectsConflictingMigrationRoutesBeforePublication() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);

        assertThatThrownBy(() -> registerModern(registrar, registeredFeature(
                "ext-a", "ext-a", 10L,
                List.of(
                        sourceDescriptor("alpha", "alpha-work", "SHARED"),
                        sourceDescriptor("beta", "beta-work", "SHARED")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduled source alias")
                .hasNoCause();
        assertThat(registry.snapshotView().owners()).isEmpty();
    }

    @Test
    @DisplayName("owner bundle 应在生成迁移 route 前拒绝重复持久化规范")
    void rejectsDuplicateLegacyPersistenceDescriptors() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            ScheduledSourceDescriptor descriptor = sourceDescriptor("alpha", "alpha-work", "ALPHA");
            registerCompleteBeans(child, List.of(descriptor));
            child.registerBean("duplicate-persistence", LegacySchedulePersistenceDescriptorProvider.class,
                    () -> () -> List.of(
                            new LegacySchedulePersistenceDescriptor(
                                    "alpha", "alpha.definition", 1, Set.of("alpha-work"),
                                    Set.of("alpha-policy")),
                            new LegacySchedulePersistenceDescriptor(
                                    "alpha", "other.definition", 2, Set.of("alpha-work"),
                                    Set.of("alpha-policy"))));
            child.refresh();

            assertThatThrownBy(() -> register(registrar, registeredFeature(
                    "ext-a", "ext-a", 10L,
                    List.of(descriptor)), child))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("duplicate legacy persistence descriptor: alpha")
                    .hasNoCause();
        }
        assertThat(registry.snapshotView().owners()).isEmpty();
    }

    @Test
    @DisplayName("单一 owner 的 child context 出现多个迁移适配器时应在发布前拒绝")
    void rejectsMultipleMigrationAdaptersBeforePublication() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);
        LegacyScheduledTaskMigrationAdapter first = snapshot ->
                new LegacyScheduledTaskMigrationResult.Rejected("NOT_USED", "{}");
        LegacyScheduledTaskMigrationAdapter second = snapshot ->
                new LegacyScheduledTaskMigrationResult.Rejected("NOT_USED", "{}");

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            ScheduledSourceDescriptor descriptor = sourceDescriptor("alpha", "alpha-work", "ALPHA");
            registerCompleteBeans(child, List.of(descriptor));
            child.registerBean("migration-one", LegacyScheduledTaskMigrationAdapter.class, () -> first);
            child.registerBean("migration-two", LegacyScheduledTaskMigrationAdapter.class, () -> second);
            child.refresh();

            assertThatThrownBy(() -> register(registrar, registeredFeature(
                    "ext-a", "ext-a", 11L,
                    List.of(descriptor)), child))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("multiple legacy schedule migration adapters")
                    .hasNoCause();
        }
        assertThat(registry.snapshotView().owners()).isEmpty();
        assertThat(ScheduleCapabilityRegistryTestAccess.publish(
                registry, ownerBundle(
                        new ScheduleCapabilityOwner("ext-b", "ext-b", 1L),
                        List.of(sourceDescriptor("beta", "beta-work", "ALPHA"))))).isNotNull();
    }

    @Test
    @DisplayName("已有 owner 的冲突 claim 必须在任何旧任务迁移之前拒绝")
    void rejectsCrossOwnerConflictBeforeMigrationSideEffects() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityRegistryTestAccess.publish(registry, ownerBundle(
                new ScheduleCapabilityOwner("ext-a", "ext-a", 1L),
                List.of(sourceDescriptor("alpha", "alpha-work", "SHARED"))));
        AtomicBoolean migrationCalled = new AtomicBoolean();
        LegacyScheduledTaskMigrationService migrationService = (reservation, adapter) -> {
            migrationCalled.set(true);
            return new LegacyScheduledTaskMigrationService.OwnerMigrationReport(
                    registry.reservedMigrationSnapshot(reservation).ownerPluginId(), 1, 1, 0, 0);
        };
        PluginScheduleContributionRegistrar registrar = registrar(registry, migrationService);

        assertThatThrownBy(() -> registerModern(registrar, registeredFeature(
                "ext-b", "ext-b", 1L,
                List.of(sourceDescriptor("beta", "beta-work", "SHARED")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate scheduled source alias")
                .hasNoCause();

        assertThat(migrationCalled).isFalse();
        assertThat(registry.snapshotView().owners())
                .extracting(view -> view.owner().featurePluginId())
                .containsExactly("ext-a");
    }

    @Test
    @DisplayName("有完整迁移规范但缺少 adapter 时使用明确拒绝结果后再发布 owner")
    void marksAdapterUnavailableWhenMigrationAdapterIsMissing() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        AtomicReference<LegacyScheduledTaskMigrationAdapter> capturedAdapter = new AtomicReference<>();
        LegacyScheduledTaskMigrationService migrationService = (reservation, adapter) -> {
            capturedAdapter.set(adapter);
            return new LegacyScheduledTaskMigrationService.OwnerMigrationReport(
                    registry.reservedMigrationSnapshot(reservation).ownerPluginId(), 1, 0, 1, 0);
        };
        PluginScheduleContributionRegistrar registrar = registrar(registry, migrationService);

        try (AnnotationConfigApplicationContext child = completeChildContext("alpha", "alpha-work")) {
            assertThat(register(registrar, registeredFeature(
                    "ext-a", "ext-a", 1L,
                    List.of(sourceDescriptor("alpha", "alpha-work", "ALPHA"))), child)).isPresent();
        }

        assertThat(capturedAdapter.get().migrate(null))
                .isEqualTo(new LegacyScheduledTaskMigrationResult.Rejected(
                        PluginScheduleContributionRegistrar.MIGRATION_ADAPTER_UNAVAILABLE, "{}"));
    }

}
