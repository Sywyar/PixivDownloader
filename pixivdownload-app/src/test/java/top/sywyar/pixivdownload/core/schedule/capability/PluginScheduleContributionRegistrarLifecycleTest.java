package top.sywyar.pixivdownload.core.schedule.capability;

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
@DisplayName("外置计划能力注册器身份与清退")
class PluginScheduleContributionRegistrarLifecycleTest extends PluginScheduleContributionRegistrarTestSupport {

    @Test
    @DisplayName("registry 提交后发生致命失败仍撤回孤儿 publication 并允许同 owner 重试")
    void postCommitFatalIsCompensatedBeforeRegistrationReturns() {
        AtomicBoolean failAfterCommit = new AtomicBoolean(true);
        OutOfMemoryError fatal = new OutOfMemoryError("post-commit");
        ScheduleCapabilityRegistry registry = ScheduleCapabilityRegistryTestAccess.withCommitProbe(() -> {
            if (failAfterCommit.getAndSet(false)) {
                throw fatal;
            }
        });
        PluginScheduleContributionRegistrar registrar = registrar(registry);
        PluginRegistry.RegisteredPlugin registered = registeredFeature(
                "ext-post-commit", "ext-post-commit", 1L,
                List.of(sourceDescriptor("alpha", "alpha-work", "ALPHA")));
        Throwable observed = null;

        try {
            registerModern(registrar, registered);
        } catch (Throwable failure) {
            observed = failure;
        }

        assertThat(observed).isSameAs(fatal);
        assertThat(registry.snapshotView().owners()).isEmpty();
        assertThat(ScheduleCapabilityRegistryTestAccess.publication(
                registrar,
                new ScheduleCapabilityOwner("ext-post-commit", "ext-post-commit", 1L))).isEmpty();
        assertThat(registerModern(registrar, registered)).isPresent();
    }

    @Test
    @DisplayName("reserve、撤回与 retirement 确认返回处的致命失败可用同一宿主身份重试")
    void fatalTransitionReturnGapsRecoverExactRegistryIdentity() {
        for (Error expected : new Error[]{new OutOfMemoryError("schedule-transition"), new ThreadDeath()}) {
            AtomicReference<Error> reserveFailure = new AtomicReference<>();
            ScheduleCapabilityRegistry reserveRegistry =
                    ScheduleCapabilityRegistryTestAccess.withTransitionProbes(
                            () -> throwPending(reserveFailure), () -> {
                            }, () -> {
                            }, () -> {
                            });
            PluginScheduleContributionRegistrar reserveRegistrar = registrar(reserveRegistry);
            PluginRegistry.RegisteredPlugin reserveRegistered = registeredFeature(
                    "ext-reserve-return", "ext-reserve-return", 1L,
                    List.of(sourceDescriptor(
                            "reserve-return", "reserve-return-work", "RESERVE_RETURN")));

            reserveFailure.set(expected);
            assertThat(catchThrowable(() -> registerModern(reserveRegistrar, reserveRegistered)))
                    .isSameAs(expected);
            assertThat(reserveRegistry.snapshotView().owners()).isEmpty();
            assertThat(registerModern(reserveRegistrar, reserveRegistered)).isPresent();

            AtomicReference<Error> withdrawFailure = new AtomicReference<>();
            ScheduleCapabilityRegistry withdrawRegistry =
                    ScheduleCapabilityRegistryTestAccess.withTransitionProbes(
                            () -> {
                            }, () -> throwPending(withdrawFailure), () -> {
                            }, () -> {
                            });
            PluginScheduleContributionRegistrar withdrawRegistrar = registrar(withdrawRegistry);
            ScheduleCapabilityPublication withdrawPublication = registerModern(
                    withdrawRegistrar,
                    registeredFeature(
                            "ext-withdraw-return", "ext-withdraw-return", 1L,
                            List.of(sourceDescriptor(
                                    "withdraw-return", "withdraw-return-work", "WITHDRAW_RETURN"))))
                    .orElseThrow();

            withdrawFailure.set(expected);
            assertThat(catchThrowable(() -> withdrawRegistrar.withdraw(AUTHORITY, withdrawPublication)))
                    .isSameAs(expected);
            ScheduleGenerationDrain recoveredDrain =
                    withdrawRegistrar.withdraw(AUTHORITY, withdrawPublication).orElseThrow();
            assertThat(recoveredDrain.isDrained()).isTrue();
            withdrawRegistrar.acknowledgeRetired(AUTHORITY, recoveredDrain);
            assertThat(withdrawRegistrar.releaseRetirementProof(AUTHORITY, recoveredDrain)).isTrue();

            AtomicReference<Error> acknowledgeFailure = new AtomicReference<>();
            ScheduleCapabilityRegistry acknowledgeRegistry =
                    ScheduleCapabilityRegistryTestAccess.withTransitionProbes(
                            () -> {
                            }, () -> {
                            }, () -> throwPending(acknowledgeFailure), () -> {
                            });
            PluginScheduleContributionRegistrar acknowledgeRegistrar = registrar(acknowledgeRegistry);
            ScheduleCapabilityPublication acknowledgePublication = registerModern(
                    acknowledgeRegistrar,
                    registeredFeature(
                            "ext-ack-return", "ext-ack-return", 1L,
                            List.of(sourceDescriptor(
                                    "ack-return", "ack-return-work", "ACK_RETURN"))))
                    .orElseThrow();
            ScheduleGenerationDrain acknowledgeDrain =
                    acknowledgeRegistrar.withdraw(AUTHORITY, acknowledgePublication).orElseThrow();

            acknowledgeFailure.set(expected);
            assertThat(catchThrowable(() ->
                    acknowledgeRegistrar.acknowledgeRetired(AUTHORITY, acknowledgeDrain)))
                    .isSameAs(expected);
            acknowledgeRegistrar.acknowledgeRetired(AUTHORITY, acknowledgeDrain);
            assertThat(acknowledgeRegistrar.releaseRetirementProof(AUTHORITY, acknowledgeDrain)).isTrue();

            AtomicReference<Error> forgetFailure = new AtomicReference<>();
            ScheduleCapabilityRegistry forgetRegistry =
                    ScheduleCapabilityRegistryTestAccess.withTransitionProbes(
                            () -> {
                            }, () -> {
                            }, () -> {
                            }, () -> throwPending(forgetFailure));
            PluginScheduleContributionRegistrar forgetRegistrar = registrar(forgetRegistry);
            ScheduleCapabilityPublication forgetPublication = registerModern(
                    forgetRegistrar,
                    registeredFeature(
                            "ext-forget-return", "ext-forget-return", 1L,
                            List.of(sourceDescriptor(
                                    "forget-return", "forget-return-work", "FORGET_RETURN"))))
                    .orElseThrow();
            ScheduleGenerationDrain forgetDrain =
                    forgetRegistrar.withdraw(AUTHORITY, forgetPublication).orElseThrow();

            forgetFailure.set(expected);
            assertThat(catchThrowable(() -> forgetRegistrar.acknowledgeRetired(AUTHORITY, forgetDrain)))
                    .isSameAs(expected);
            forgetRegistrar.acknowledgeRetired(AUTHORITY, forgetDrain);
            assertThat(forgetRegistrar.releaseRetirementProof(AUTHORITY, forgetDrain)).isTrue();
        }
    }

    @Test
    @DisplayName("schedule mutation 需要不可构造授权且只接受当前 active RegisteredPlugin 对象")
    void mutationRequiresOpaqueAuthorityAndActiveRegistrationIdentity() {
        ScheduleCapabilityRegistry capabilityRegistry = new ScheduleCapabilityRegistry();
        PluginRegistry activeRegistry = new PluginRegistry(List.of());
        PluginRegistry.RegisteredPlugin actual = registeredFeature(
                "ext-authorized", "ext-authorized", 1L,
                List.of(sourceDescriptor("authorized", "authorized-work", "AUTHORIZED")));
        activeRegistry.register(actual);
        LegacyScheduledTaskMigrationService migrationService = (reservation, adapter) ->
                new LegacyScheduledTaskMigrationService.OwnerMigrationReport(
                        capabilityRegistry.reservedMigrationSnapshot(reservation).ownerPluginId(),
                        0, 0, 0, 0);
        PluginScheduleContributionRegistrar registrar =
                ScheduleCapabilityRegistryTestAccess.registrar(
                        capabilityRegistry, migrationService, activeRegistry);
        PluginRegistry.RegisteredPlugin forged = new PluginRegistry.RegisteredPlugin(
                actual.plugin(), actual.source(), actual.classLoader(),
                actual.packageId(), actual.generation());

        assertThat(forged).isEqualTo(actual).isNotSameAs(actual);
        assertThatThrownBy(() -> registrar.register(null, actual, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("authority");
        assertThatThrownBy(() -> registrar.register(AUTHORITY, forged, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current active plugin identity");
        assertThat(capabilityRegistry.snapshotView().owners()).isEmpty();
        try (AnnotationConfigApplicationContext child = completeChildContext(
                actual.plugin().scheduledSourceDescriptors())) {
            assertThat(registrar.register(AUTHORITY, actual, child)).isPresent();
        }

        assertThat(PluginScheduleContributionRegistrar.class.getDeclaredConstructors())
                .allMatch(constructor -> !Modifier.isPublic(constructor.getModifiers()));
        assertThat(ScheduleContributionLifecycleAuthority.class.getDeclaredConstructors())
                .allMatch(constructor -> !Modifier.isPublic(constructor.getModifiers()));
    }

    @Test
    @DisplayName("getter 阻塞期间身份被替换时旧 owner 不迁移也不发布")
    void replacementDuringBlockingGetterPreventsStaleMigrationAndPublication() throws Exception {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginRegistry activeRegistry = new PluginRegistry(List.of());
        CountDownLatch getterEntered = new CountDownLatch(1);
        CountDownLatch releaseGetter = new CountDownLatch(1);
        AtomicBoolean migrationCalled = new AtomicBoolean();
        LegacyScheduledTaskMigrationService migrationService = (reservation, adapter) -> {
            migrationCalled.set(true);
            return new LegacyScheduledTaskMigrationService.OwnerMigrationReport(
                    registry.reservedMigrationSnapshot(reservation).ownerPluginId(), 0, 0, 0, 0);
        };
        PluginScheduleContributionRegistrar registrar = ScheduleCapabilityRegistryTestAccess.registrar(
                registry, migrationService, activeRegistry);
        PixivFeaturePlugin blockingPlugin = new PixivFeaturePlugin() {
            @Override public String id() { return "ext-race"; }
            @Override public String displayName() { return "ext-race.label"; }
            @Override public String description() { return "ext-race.summary"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<ScheduledSourceDescriptor> scheduledSourceDescriptors() {
                getterEntered.countDown();
                awaitLatch(releaseGetter);
                return List.of(sourceDescriptor("alpha", "alpha-work", "ALPHA"));
            }
        };
        PluginRegistry.RegisteredPlugin stale = new PluginRegistry.RegisteredPlugin(
                blockingPlugin, PluginSource.EXTERNAL, getClass().getClassLoader(),
                "ext-race", 1L);
        PluginRegistry.RegisteredPlugin replacement = registeredFeature(
                "ext-race", "ext-race", 2L, List.of());
        activeRegistry.register(stale);
        AtomicReference<Throwable> registrationFailure = new AtomicReference<>();

        try (AnnotationConfigApplicationContext child = legacyMigrationChild(
                "alpha", "alpha-work",
                snapshot -> new LegacyScheduledTaskMigrationResult.Rejected("NOT_USED", "{}"))) {
            Thread registration = new Thread(() -> {
                try {
                    registrar.register(AUTHORITY, stale, child);
                } catch (Throwable failure) {
                    registrationFailure.set(failure);
                }
            }, "stale-schedule-registration");
            registration.start();
            try {
                assertThat(getterEntered.await(5, TimeUnit.SECONDS)).isTrue();
                activeRegistry.unregister(stale.id());
                activeRegistry.register(replacement);
            } finally {
                releaseGetter.countDown();
            }
            registration.join(5000);
            assertThat(registration.isAlive()).isFalse();
        }

        assertThat(registrationFailure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current active identity");
        assertThat(migrationCalled).isFalse();
        assertThat(activeRegistry.registeredPlugins()).containsExactly(replacement);
        assertThat(registry.snapshotView().owners()).isEmpty();
        assertThat(ScheduleCapabilityRegistryTestAccess.publication(
                registrar,
                new ScheduleCapabilityOwner("ext-race", "ext-race", 1L))).isEmpty();
    }

    @Test
    @DisplayName("迁移身份 reservation 阻止替换插入副作用与 publication 之间")
    void migrationIdentityReservationSerializesReplacementThroughPublication() throws Exception {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginRegistry activeRegistry = new PluginRegistry(List.of());
        CountDownLatch adapterEntered = new CountDownLatch(1);
        CountDownLatch releaseAdapter = new CountDownLatch(1);
        CountDownLatch removalStarted = new CountDownLatch(1);
        CountDownLatch removalFinished = new CountDownLatch(1);
        AtomicBoolean migrationSideEffect = new AtomicBoolean();
        LegacyScheduledTaskMigrationService migrationService = (reservation, adapter) -> {
            adapter.migrate(null);
            return new LegacyScheduledTaskMigrationService.OwnerMigrationReport(
                    registry.reservedMigrationSnapshot(reservation).ownerPluginId(), 1, 1, 0, 0);
        };
        PluginScheduleContributionRegistrar registrar = ScheduleCapabilityRegistryTestAccess.registrar(
                registry, migrationService, activeRegistry);
        PluginRegistry.RegisteredPlugin registered = registeredFeature(
                "ext-migrate", "ext-migrate", 1L,
                List.of(sourceDescriptor("alpha", "alpha-work", "ALPHA")));
        PluginRegistry.RegisteredPlugin replacement = registeredFeature(
                "ext-migrate", "ext-migrate", 2L, List.of());
        activeRegistry.register(registered);
        AtomicReference<ScheduleCapabilityPublication> published = new AtomicReference<>();
        AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
        AtomicReference<Throwable> removalFailure = new AtomicReference<>();

        LegacyScheduledTaskMigrationAdapter adapter = snapshot -> {
            adapterEntered.countDown();
            awaitLatch(releaseAdapter);
            migrationSideEffect.set(true);
            return new LegacyScheduledTaskMigrationResult.Rejected("TEST_ONLY", "{}");
        };
        try (AnnotationConfigApplicationContext child =
                     legacyMigrationChild("alpha", "alpha-work", adapter)) {
            Thread registration = new Thread(() -> {
                try {
                    published.set(registrar.register(AUTHORITY, registered, child).orElseThrow());
                } catch (Throwable failure) {
                    registrationFailure.set(failure);
                }
            }, "reserved-schedule-registration");
            registration.start();
            assertThat(adapterEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Thread replacementThread = new Thread(() -> {
                removalStarted.countDown();
                try {
                    activeRegistry.unregister(registered.id());
                    activeRegistry.register(replacement);
                } catch (Throwable failure) {
                    removalFailure.set(failure);
                } finally {
                    removalFinished.countDown();
                }
            }, "schedule-identity-replacement");
            replacementThread.start();
            assertThat(removalStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(removalFinished.await(150, TimeUnit.MILLISECONDS))
                    .as("identity replacement 必须等待迁移与 publication")
                    .isFalse();

            releaseAdapter.countDown();
            registration.join(5000);
            replacementThread.join(5000);
            assertThat(registration.isAlive()).isFalse();
            assertThat(replacementThread.isAlive()).isFalse();
        }

        assertThat(registrationFailure.get()).isNull();
        assertThat(removalFailure.get()).isNull();
        assertThat(migrationSideEffect).isTrue();
        assertThat(published.get()).isNotNull();
        assertThat(ScheduleCapabilityRegistryTestAccess.publication(
                registrar, published.get().owner())).containsSame(published.get());
        assertThat(registry.snapshotView().owners()).singleElement().satisfies(owner ->
                assertThat(owner.owner()).isEqualTo(published.get().owner()));
        assertThat(activeRegistry.registeredPlugins()).containsExactly(replacement);
        registrar.withdraw(AUTHORITY, published.get()).orElseThrow();
    }

    @Test
    @DisplayName("迁移失败先释放 claim 再允许身份替换完成")
    void migrationFailureCleansReservationsBeforeReplacementCompletes() throws Exception {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginRegistry activeRegistry = new PluginRegistry(List.of());
        CountDownLatch adapterEntered = new CountDownLatch(1);
        CountDownLatch releaseAdapter = new CountDownLatch(1);
        CountDownLatch removalFinished = new CountDownLatch(1);
        LegacyScheduledTaskMigrationService migrationService = (reservation, adapter) -> {
            adapter.migrate(null);
            throw new IllegalStateException("migration rejected for test");
        };
        PluginScheduleContributionRegistrar registrar = ScheduleCapabilityRegistryTestAccess.registrar(
                registry, migrationService, activeRegistry);
        PluginRegistry.RegisteredPlugin registered = registeredFeature(
                "ext-failed-migrate", "ext-failed-migrate", 1L,
                List.of(sourceDescriptor("alpha", "alpha-work", "ALPHA")));
        PluginRegistry.RegisteredPlugin replacement = registeredFeature(
                "ext-failed-migrate", "ext-failed-migrate", 2L, List.of());
        activeRegistry.register(registered);
        AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
        AtomicReference<Throwable> removalFailure = new AtomicReference<>();
        AtomicReference<ScheduleCapabilityPublication> reusedClaimPublication = new AtomicReference<>();

        LegacyScheduledTaskMigrationAdapter adapter = snapshot -> {
            adapterEntered.countDown();
            awaitLatch(releaseAdapter);
            return new LegacyScheduledTaskMigrationResult.Rejected("TEST_ONLY", "{}");
        };
        try (AnnotationConfigApplicationContext child =
                     legacyMigrationChild("alpha", "alpha-work", adapter)) {
            Thread registration = new Thread(() -> {
                try {
                    registrar.register(AUTHORITY, registered, child);
                } catch (Throwable failure) {
                    registrationFailure.set(failure);
                }
            }, "failing-schedule-migration");
            registration.start();
            assertThat(adapterEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Thread replacementThread = new Thread(() -> {
                try {
                    activeRegistry.unregister(registered.id());
                    activeRegistry.register(replacement);
                    reusedClaimPublication.set(ScheduleCapabilityRegistryTestAccess.publish(
                            registry,
                            ownerBundle(
                                    new ScheduleCapabilityOwner(
                                            "claim-reuser", "claim-reuser-package", 1L),
                                    List.of(sourceDescriptor(
                                            "replacement-alpha", "replacement-work", "ALPHA")))));
                } catch (Throwable failure) {
                    removalFailure.set(failure);
                } finally {
                    removalFinished.countDown();
                }
            }, "replacement-after-migration-failure");
            replacementThread.start();
            assertThat(removalFinished.await(150, TimeUnit.MILLISECONDS))
                    .as("identity replacement 必须等待失败 claim 清理")
                    .isFalse();

            releaseAdapter.countDown();
            registration.join(5000);
            replacementThread.join(5000);
            assertThat(registration.isAlive()).isFalse();
            assertThat(replacementThread.isAlive()).isFalse();
        }

        assertThat(registrationFailure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("migration rejected for test");
        assertThat(removalFailure.get()).isNull();
        assertThat(activeRegistry.registeredPlugins()).containsExactly(replacement);
        assertThat(registry.snapshotView().owners()).singleElement().satisfies(owner ->
                assertThat(owner.owner().featurePluginId()).isEqualTo("claim-reuser"));
        assertThat(ScheduleCapabilityRegistryTestAccess.publication(
                registrar,
                new ScheduleCapabilityOwner(
                        "ext-failed-migrate", "ext-failed-migrate", 1L))).isEmpty();
        assertThat(reusedClaimPublication.get())
                .as("identity replacement 完成时失败 migration 的 claim 已可复用")
                .isNotNull();
    }

    @Test
    @DisplayName("精确 publication 撤回会拒绝新 lease、取消旧 lease，并在旧 lease 释放后归零")
    void exactWithdrawalReturnsGenerationDrain() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);
        ScheduleCapabilityPublication publication = registerModern(registrar,
                registeredFeature("ext-a", "ext-a", 3L,
                        List.of(sourceDescriptor("alpha", "alpha-work", "ALPHA"))))
                .orElseThrow();
        SchedulePlanningLease lease = registry.prepareSource("alpha").orElseThrow();
        assertThat(registry.activate(lease)).isTrue();

        ScheduleGenerationDrain drain = registrar.withdraw(AUTHORITY, publication).orElseThrow();

        assertThat(registry.prepareSource("alpha")).isEmpty();
        assertThat(lease.cancellation().isCancellationRequested()).isTrue();
        assertThat(drain.activeLeaseCount()).isEqualTo(1);
        assertThat(drain.isDrained()).isFalse();
        lease.close();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("同 generation 重新发布后旧 token 不能撤回新 publication")
    void stalePublicationCannotWithdrawReplacement() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);
        PluginRegistry.RegisteredPlugin registered = registeredFeature(
                "ext-a", "ext-a", 4L,
                List.of(sourceDescriptor("alpha", "alpha-work", "ALPHA")));
        ScheduleCapabilityPublication oldPublication = registerModern(registrar, registered).orElseThrow();
        ScheduleGenerationDrain oldDrain = registrar.withdraw(AUTHORITY, oldPublication).orElseThrow();
        registrar.acknowledgeRetired(AUTHORITY, oldDrain);
        assertThat(registrar.releaseRetirementProof(AUTHORITY, oldDrain)).isTrue();
        ScheduleCapabilityPublication current = registerModern(registrar, registered).orElseThrow();
        ScheduleCapabilityPublication forged =
                ScheduleCapabilityRegistryTestAccess.equivalent(current);

        assertThat(current.publicationId()).isGreaterThan(oldPublication.publicationId());
        assertThat(registrar.withdraw(AUTHORITY, oldPublication)).isEmpty();
        assertThat(registrar.withdraw(AUTHORITY, forged)).isEmpty();
        assertThat(registry.resolveSourceDescriptor("alpha")).isPresent();
        assertThat(registrar.withdraw(AUTHORITY, current)).isPresent();
    }

    @Test
    @DisplayName("registry 未确认撤回时保留 publication token 供一致性诊断与重试")
    void failedRegistryWithdrawalKeepsPublicationToken() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);
        ScheduleCapabilityOwner owner = new ScheduleCapabilityOwner("ext-a", "ext-a", 6L);
        ScheduleCapabilityPublication publication = registerModern(registrar, registeredFeature(
                "ext-a", "ext-a", 6L,
                List.of(sourceDescriptor("alpha", "alpha-work"))))
                .orElseThrow();
        ScheduleGenerationDrain drain =
                ScheduleCapabilityRegistryTestAccess.withdraw(registry, publication).orElseThrow();

        assertThat(registrar.withdraw(AUTHORITY, publication)).containsSame(drain);
        assertThat(ScheduleCapabilityRegistryTestAccess.publication(registrar, owner))
                .containsSame(publication);
    }

}
