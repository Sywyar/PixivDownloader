package top.sywyar.pixivdownload.core.schedule.capability;

/**
 * 仅供 plugin-runtime 测试调用 registry 包级发布与故障探针入口。
 */
final class ScheduleCapabilityRegistryTestAccess {

    private ScheduleCapabilityRegistryTestAccess() {
    }

    static ScheduleCapabilityPublication publish(
            ScheduleCapabilityRegistry registry,
            ScheduleOwnerBundle bundle) {
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

    static ScheduleCapabilityRegistry withAcquireProbe(Runnable postAcquireProbe) {
        return new ScheduleCapabilityRegistry(ignored -> true, () -> {
        }, postAcquireProbe);
    }

    static ScheduleCapabilityRegistry withReleaseProbes(
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

    static ScheduleCapabilityPublication equivalent(
            ScheduleCapabilityPublication publication) {
        return new ScheduleCapabilityPublication(
                publication.owner(), publication.publicationId());
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
