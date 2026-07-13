package top.sywyar.pixivdownload.core.schedule.capability;

import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebAssetValidator;

import java.util.Optional;
import java.util.function.Predicate;

/** 仅供 app 测试调用 registry 包级生命周期入口与构造伪造身份；生产代码没有对应入口。 */
public final class ScheduleCapabilityRegistryTestAccess {

    private ScheduleCapabilityRegistryTestAccess() {
    }

    public static ScheduleCapabilityReservation reserve(
            ScheduleCapabilityRegistry registry, ScheduleOwnerBundle bundle) {
        ScheduleCapabilityReservation reservation = registry.allocateReservation(bundle.owner());
        registry.reserve(reservation, bundle);
        return reservation;
    }

    public static ScheduleCapabilityReservation equivalent(
            ScheduleCapabilityReservation reservation) {
        return new ScheduleCapabilityReservation(
                reservation.owner(), reservation.reservationId());
    }

    public static boolean release(
            ScheduleCapabilityRegistry registry, ScheduleCapabilityReservation reservation) {
        return registry.release(reservation);
    }

    public static ScheduleCapabilityPublication publish(
            ScheduleCapabilityRegistry registry, ScheduleOwnerBundle bundle) {
        ScheduleCapabilityReservation reservation = registry.allocateReservation(bundle.owner());
        try {
            registry.reserve(reservation, bundle);
            return registry.commit(reservation);
        } catch (Throwable failure) {
            try {
                ScheduleGenerationDrain drain = registry.rollback(reservation);
                if (drain != null) {
                    while (!drain.awaitDrained()) {
                        Thread.interrupted();
                    }
                    registry.acknowledgeRetired(drain);
                    registry.forgetRetirementAcknowledgement(drain);
                }
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            rethrowUnchecked(failure);
            throw new IllegalStateException("unreachable");
        }
    }

    public static ScheduleCapabilityRegistry withCommitProbe(Runnable postCommitProbe) {
        return new ScheduleCapabilityRegistry(ignored -> true, postCommitProbe);
    }

    public static ScheduleCapabilityRegistry withAcquireProbe(Runnable postAcquireProbe) {
        return new ScheduleCapabilityRegistry(ignored -> true, () -> {
        }, postAcquireProbe);
    }

    public static ScheduleCapabilityRegistry withReleaseProbes(
            Runnable beforeReleaseProbe,
            Runnable afterReleaseProbe) {
        return new ScheduleCapabilityRegistry(
                ignored -> true,
                () -> {
                },
                () -> {
                },
                () -> {
                },
                beforeReleaseProbe,
                afterReleaseProbe);
    }

    public static ScheduleCapabilityRegistry withTransitionProbes(
            Runnable postReserveProbe,
            Runnable postWithdrawProbe,
            Runnable postRetirementAcknowledgeProbe,
            Runnable postRetirementForgetProbe) {
        return new ScheduleCapabilityRegistry(
                ignored -> true,
                postReserveProbe,
                () -> {
                },
                postWithdrawProbe,
                postRetirementAcknowledgeProbe,
                postRetirementForgetProbe,
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                });
    }

    public static ScheduleCapabilityRegistry withAdmission(Predicate<String> ownerAdmission) {
        return new ScheduleCapabilityRegistry(ownerAdmission, () -> {
        });
    }

    public static Optional<ScheduleGenerationDrain> withdraw(
            ScheduleCapabilityRegistry registry, ScheduleCapabilityPublication publication) {
        return registry.withdraw(publication);
    }

    public static Optional<ScheduleCapabilityPublication> publication(
            ScheduleCapabilityRegistry registry, ScheduleCapabilityOwner owner) {
        return registry.publication(owner);
    }

    public static ScheduleCapabilityPublication equivalent(
            ScheduleCapabilityPublication publication) {
        return new ScheduleCapabilityPublication(
                publication.owner(), publication.publicationId());
    }

    public static PluginScheduleContributionRegistrar registrar(
            ScheduleCapabilityRegistry registry,
            LegacyScheduledTaskMigrationService migrationService,
            PluginRegistry pluginRegistry) {
        return registrar(registry, migrationService, pluginRegistry, () -> {
        });
    }

    public static PluginScheduleContributionRegistrar registrar(
            ScheduleCapabilityRegistry registry,
            LegacyScheduledTaskMigrationService migrationService,
            PluginRegistry pluginRegistry,
            Runnable rollbackCleanupProbe) {
        StaticResourceRegistry staticResources = new StaticResourceRegistry(pluginRegistry);
        return new PluginScheduleContributionRegistrar(
                registry,
                migrationService,
                pluginRegistry,
                new PluginOwnedWebAssetValidator(staticResources),
                rollbackCleanupProbe);
    }

    public static Optional<ScheduleCapabilityPublication> publication(
            PluginScheduleContributionRegistrar registrar,
            ScheduleCapabilityOwner owner) {
        return registrar.publication(owner);
    }

    private static void rethrowUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("schedule test publication failed", failure);
    }
}
