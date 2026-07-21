package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.capability.SchedulePlanningLease;
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
@DisplayName("外置插件 schedule 能力原子注册器")
class PluginScheduleContributionRegistrarTest {

    private static final ScheduleContributionLifecycleAuthority AUTHORITY =
            ScheduleContributionLifecycleAuthorityTestAccess.create();
    private static final Map<PluginScheduleContributionRegistrar, PluginRegistry> ACTIVE_REGISTRIES =
            new ConcurrentHashMap<>();

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
    @DisplayName("一次发布来源描述符与 child context 四类行为 Bean，并由宿主盖章 owner、package 和 generation")
    void publishesCompleteOwnerBundle() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);

        try (AnnotationConfigApplicationContext child = completeChildContext("alpha", "alpha-work")) {
            ScheduleCapabilityPublication publication = register(registrar,
                    registeredFeature("ext-a", "ext-a", 7L,
                            List.of(sourceDescriptor("alpha", "alpha-work", "ALPHA"))), child).orElseThrow();

            assertThat(publication.owner()).isEqualTo(
                    new ScheduleCapabilityOwner("ext-a", "ext-a", 7L));
            assertThat(registry.snapshotView().owners()).singleElement().satisfies(owner -> {
                assertThat(owner.owner()).isEqualTo(publication.owner());
                assertThat(owner.sourceTypes()).containsExactly("alpha");
                assertThat(owner.sourceAliases()).containsExactly("ALPHA");
                assertThat(owner.workTypes()).containsExactly("alpha-work");
                assertThat(owner.credentialPolicyIds()).containsExactly("alpha-policy");
                assertThat(owner.guardIds()).containsExactly("alpha-guard");
            });
            assertThat(registry.resolveSourceDescriptor("ALPHA")).isPresent();
            assertThat(registry.resolveSourceExecutor("ALPHA")).isPresent();
            assertThat(registry.resolveWorkExecutor("alpha-work")).isPresent();
            assertThat(registry.resolveCredentialPolicy("alpha-policy")).isPresent();
            assertThat(registry.resolveGuard("alpha-guard")).isPresent();
        }
    }

    @Test
    @DisplayName("插件 getter 或 bundle 校验失败时旧 snapshot 完整保留，不产生 publication")
    void preparationFailureDoesNotPolluteSnapshot() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);
        registerModern(registrar, registeredFeature("ext-a", "ext-a", 1L,
                List.of(sourceDescriptor("stable", "stable-work", "STABLE")))).orElseThrow();
        long revision = registry.snapshotView().revision();

        PixivFeaturePlugin broken = new PixivFeaturePlugin() {
            @Override public String id() { return "ext-b"; }
            @Override public String displayName() { return "ext-b.label"; }
            @Override public String description() { return "ext-b.summary"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override
            public List<ScheduledSourceDescriptor> scheduledSourceDescriptors() {
                throw new IllegalStateException("broken getter");
            }
        };
        PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                broken, PluginSource.EXTERNAL, getClass().getClassLoader(), "ext-b", 2L);

        assertThatThrownBy(() -> register(registrar, registered, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduledSourceDescriptors")
                .hasMessageContaining("IllegalStateException")
                .hasMessageNotContaining("broken getter")
                .hasNoCause();
        assertThat(registry.snapshotView().revision()).isEqualTo(revision);
        assertThat(registry.resolveSourceDescriptor("stable")).isPresent();
        assertThat(ScheduleCapabilityRegistryTestAccess.publication(
                registrar, new ScheduleCapabilityOwner("ext-b", "ext-b", 2L))).isEmpty();
    }

    @Test
    @DisplayName("插件贡献 getter 抛断言错误时无 cause 且 schedule 快照保持不变")
    void contributionGetterAssertionErrorIsNormalizedAtBoundary() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);
        AtomicInteger reads = new AtomicInteger();
        var before = registry.snapshotView();
        PixivFeaturePlugin broken = new PixivFeaturePlugin() {
            @Override public String id() { return "ext-assert"; }
            @Override public String displayName() { return "ext-assert.label"; }
            @Override public String description() { return "ext-assert.summary"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<ScheduledSourceDescriptor> scheduledSourceDescriptors() {
                reads.incrementAndGet();
                throw new AssertionError("plugin-private-getter-failure");
            }
        };
        PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                broken,
                PluginSource.EXTERNAL,
                getClass().getClassLoader(),
                "ext-assert",
                1L);

        assertThatThrownBy(() -> register(registrar, registered, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduledSourceDescriptors")
                .hasMessageContaining("AssertionError")
                .hasMessageNotContaining("plugin-private-getter-failure")
                .hasNoCause();
        assertThat(reads).hasValue(1);
        assertThat(registry.snapshotView()).isEqualTo(before);
    }

    @Test
    @DisplayName("child Bean 能力 getter 抛断言错误时无 cause 且旧 owner 快照不变")
    void childCapabilityGetterAssertionErrorIsNormalizedAtBoundary() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);
        registerModern(registrar, registeredFeature(
                "stable-owner", "stable-owner", 1L,
                List.of(sourceDescriptor("stable-source", "stable-work")))).orElseThrow();
        var before = registry.snapshotView();
        ScheduledSourceExecutor brokenExecutor = mock(ScheduledSourceExecutor.class);
        when(brokenExecutor.sourceType()).thenThrow(new AssertionError("plugin-private-source-type"));

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.registerBean("broken-source-executor", ScheduledSourceExecutor.class,
                    () -> brokenExecutor);
            child.refresh();

            assertThatThrownBy(() -> register(
                    registrar,
                    registeredFeature(
                            "broken-owner", "broken-owner", 2L,
                            List.of()),
                    child))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("source executor type")
                    .hasMessageContaining("AssertionError")
                    .hasMessageNotContaining("plugin-private-source-type")
                    .hasNoCause();
        }

        assertThat(registry.snapshotView()).isEqualTo(before);
        assertThat(ScheduleCapabilityRegistryTestAccess.publication(
                registrar,
                new ScheduleCapabilityOwner("broken-owner", "broken-owner", 2L))).isEmpty();
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

    @Test
    @DisplayName("child context Bean 发现不含父 context，并在 descriptor/executor 不匹配时原子拒绝")
    void childBeanDiscoveryExcludesAncestors() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);
        ScheduledSourceExecutor parentExecutor = sourceExecutor("parent");

        try (AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
             AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            parent.registerBean("parent-source", ScheduledSourceExecutor.class, () -> parentExecutor);
            parent.refresh();
            child.setParent(parent);
            registerCompleteBeans(child, "alpha", "alpha-work");
            child.refresh();

            assertThat(register(registrar, registeredFeature(
                    "ext-a", "ext-a", 5L,
                    List.of(sourceDescriptor("alpha", "alpha-work"))), child)).isPresent();
            assertThat(registry.resolveSourceExecutor("alpha")).isPresent();
            assertThat(registry.resolveSourceExecutor("parent")).isEmpty();
        }
    }

    @Test
    @DisplayName("无任何 schedule 能力的插件返回空 publication 且不改变 registry")
    void emptyPluginIsTransparent() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);

        Optional<ScheduleCapabilityPublication> publication = register(registrar,
                registeredFeature("ext-a", "ext-a", 0L, List.of()), null);

        assertThat(publication).isEmpty();
        assertThat(registry.snapshotView().owners()).isEmpty();
    }

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

    private static AnnotationConfigApplicationContext completeChildContext(String sourceType, String workType) {
        return completeChildContext(List.of(sourceDescriptor(sourceType, workType)));
    }

    private static AnnotationConfigApplicationContext completeChildContext(
            List<ScheduledSourceDescriptor> descriptors) {
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        registerCompleteBeans(child, descriptors);
        child.refresh();
        return child;
    }

    private static AnnotationConfigApplicationContext legacyMigrationChild(
            String sourceType,
            String workType,
            LegacyScheduledTaskMigrationAdapter adapter) {
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        ScheduledSourceDescriptor descriptor = sourceDescriptor(sourceType, workType, sourceType.toUpperCase());
        registerCompleteBeans(child, List.of(descriptor));
        child.registerBean("legacy-migration-adapter", LegacyScheduledTaskMigrationAdapter.class, () -> adapter);
        child.registerBean("legacy-persistence", LegacySchedulePersistenceDescriptorProvider.class,
                () -> () -> List.of(new LegacySchedulePersistenceDescriptor(
                        sourceType, sourceType + ".definition", 1,
                        Set.of(workType), descriptor.credentialPolicyIds())));
        child.refresh();
        return child;
    }

    private static PluginScheduleContributionRegistrar registrar(ScheduleCapabilityRegistry registry) {
        LegacyScheduledTaskMigrationService noOp = (reservation, adapter) ->
                new LegacyScheduledTaskMigrationService.OwnerMigrationReport(
                        registry.reservedMigrationSnapshot(reservation).ownerPluginId(), 0, 0, 0, 0);
        return registrar(registry, noOp);
    }

    private static PluginScheduleContributionRegistrar registrar(
            ScheduleCapabilityRegistry registry,
            LegacyScheduledTaskMigrationService migrationService) {
        PluginRegistry activeRegistry = new PluginRegistry(List.of());
        PluginScheduleContributionRegistrar registrar =
                ScheduleCapabilityRegistryTestAccess.registrar(
                        registry, migrationService, activeRegistry);
        ACTIVE_REGISTRIES.put(registrar, activeRegistry);
        return registrar;
    }

    private static Optional<ScheduleCapabilityPublication> register(
            PluginScheduleContributionRegistrar registrar,
            PluginRegistry.RegisteredPlugin registered,
            ConfigurableApplicationContext childContext) {
        PluginRegistry activeRegistry = ACTIVE_REGISTRIES.get(registrar);
        if (activeRegistry == null) {
            throw new IllegalStateException("missing active plugin registry for registrar test");
        }
        if (activeRegistry.registeredPlugins().stream()
                .anyMatch(current -> current.id().equals(registered.id()))) {
            activeRegistry.unregister(registered.id());
        }
        activeRegistry.register(registered);
        return registrar.register(AUTHORITY, registered, childContext);
    }

    private static Optional<ScheduleCapabilityPublication> registerModern(
            PluginScheduleContributionRegistrar registrar,
            PluginRegistry.RegisteredPlugin registered) {
        try (AnnotationConfigApplicationContext child = completeChildContext(
                registered.plugin().scheduledSourceDescriptors())) {
            return register(registrar, registered, child);
        }
    }

    private static void registerCompleteBeans(
            AnnotationConfigApplicationContext child, String sourceType, String workType) {
        registerCompleteBeans(child, List.of(sourceDescriptor(sourceType, workType)));
    }

    private static void registerCompleteBeans(
            AnnotationConfigApplicationContext child,
            List<ScheduledSourceDescriptor> descriptors) {
        LinkedHashSet<String> workTypes = new LinkedHashSet<>();
        LinkedHashSet<String> policyIds = new LinkedHashSet<>();
        LinkedHashSet<String> guardIds = new LinkedHashSet<>();
        for (int index = 0; index < descriptors.size(); index++) {
            ScheduledSourceDescriptor descriptor = descriptors.get(index);
            String beanSuffix = Integer.toString(index);
            child.registerBean("source-executor-" + beanSuffix, ScheduledSourceExecutor.class,
                    () -> sourceExecutor(descriptor.sourceType()));
            workTypes.addAll(descriptor.possibleWorkTypes());
            policyIds.addAll(descriptor.credentialPolicyIds());
            guardIds.addAll(descriptor.guardIds());
        }
        for (String workType : workTypes) {
            child.registerBean("work-executor-" + workType, ScheduledWorkExecutor.class,
                    () -> workExecutor(workType));
        }
        for (String policyId : policyIds) {
            child.registerBean("credential-policy-" + policyId, ScheduledCredentialPolicy.class,
                    () -> credentialPolicy(policyId));
        }
        for (String guardId : guardIds) {
            child.registerBean("execution-guard-" + guardId, ScheduledExecutionGuard.class,
                    () -> executionGuard(guardId));
        }
    }

    private static PluginRegistry.RegisteredPlugin registeredFeature(
            String id, String packageId, long generation,
            List<ScheduledSourceDescriptor> descriptors) {
        return new PluginRegistry.RegisteredPlugin(
                feature(id, descriptors), PluginSource.EXTERNAL,
                PluginScheduleContributionRegistrarTest.class.getClassLoader(), packageId, generation);
    }

    private static PixivFeaturePlugin feature(
            String id,
            List<ScheduledSourceDescriptor> descriptors) {
        return new PixivFeaturePlugin() {
            @Override public String id() { return id; }
            @Override public String displayName() { return id + ".label"; }
            @Override public String description() { return id + ".summary"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<StaticResourceContribution> staticResources() {
                return List.of(new StaticResourceContribution(
                        id, "classpath:/test/", "/test/"));
            }
            @Override public List<ScheduledSourceDescriptor> scheduledSourceDescriptors() { return descriptors; }
        };
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timeout");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted");
        }
    }

    private static void throwPending(AtomicReference<Error> pending) {
        Error failure = pending.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
    }

    private static ScheduledSourceDescriptor sourceDescriptor(
            String sourceType, String workType, String... legacyAliases) {
        return sourceDescriptor(
                sourceType,
                workType,
                Set.of(sourceType + "-policy"),
                Set.of(sourceType + "-guard"),
                legacyAliases);
    }

    private static ScheduledSourceDescriptor sourceDescriptor(
            String sourceType,
            String workType,
            Set<String> credentialPolicyIds,
            Set<String> guardIds,
            String... legacyAliases) {
        return new ScheduledSourceDescriptor(
                sourceType, Set.of(legacyAliases), sourceType + ".definition", 1,
                new ScheduledSourcePresentation("test", "source.name", "source.description", "schedule", "neutral"),
                Set.of("schedule"), Set.of(workType), credentialPolicyIds, guardIds,
                null);
    }

    private static ScheduleOwnerBundle ownerBundle(
            ScheduleCapabilityOwner owner,
            List<ScheduledSourceDescriptor> descriptors) {
        return ownerBundle(owner, descriptors, List.of());
    }

    private static ScheduleOwnerBundle ownerBundle(
            ScheduleCapabilityOwner owner,
            List<ScheduledSourceDescriptor> descriptors,
            List<ScheduledCredentialPolicy> additionalPolicies) {
        LinkedHashSet<String> workTypes = new LinkedHashSet<>();
        LinkedHashSet<String> policyIds = new LinkedHashSet<>();
        LinkedHashSet<String> guardIds = new LinkedHashSet<>();
        descriptors.forEach(descriptor -> {
            workTypes.addAll(descriptor.possibleWorkTypes());
            policyIds.addAll(descriptor.credentialPolicyIds());
            guardIds.addAll(descriptor.guardIds());
        });
        List<ScheduledCredentialPolicy> policies = new ArrayList<>();
        policyIds.stream().map(PluginScheduleContributionRegistrarTest::credentialPolicy)
                .forEach(policies::add);
        policies.addAll(additionalPolicies);
        return ScheduleOwnerBundle.prepare(
                owner,
                descriptors,
                descriptors.stream().map(descriptor -> sourceExecutor(descriptor.sourceType())).toList(),
                workTypes.stream().map(PluginScheduleContributionRegistrarTest::workExecutor).toList(),
                policies,
                guardIds.stream().map(PluginScheduleContributionRegistrarTest::executionGuard).toList());
    }

    private static ScheduledSourceExecutor sourceExecutor(String sourceType) {
        ScheduledSourceExecutor executor = mock(ScheduledSourceExecutor.class);
        when(executor.sourceType()).thenReturn(sourceType);
        return executor;
    }

    private static ScheduledWorkExecutor workExecutor(String workType) {
        ScheduledWorkExecutor executor = mock(ScheduledWorkExecutor.class);
        when(executor.workType()).thenReturn(workType);
        return executor;
    }

    private static ScheduledCredentialPolicy credentialPolicy(String policyId) {
        ScheduledCredentialPolicy policy = mock(ScheduledCredentialPolicy.class);
        when(policy.policyId()).thenReturn(policyId);
        return policy;
    }

    private static ScheduledExecutionGuard executionGuard(String guardId) {
        ScheduledExecutionGuard guard = mock(ScheduledExecutionGuard.class);
        when(guard.guardId()).thenReturn(guardId);
        return guard;
    }
}
