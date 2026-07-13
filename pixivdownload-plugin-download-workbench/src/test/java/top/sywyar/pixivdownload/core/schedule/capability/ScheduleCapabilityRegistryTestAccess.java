package top.sywyar.pixivdownload.core.schedule.capability;

import java.util.Optional;

/** 仅供 download-workbench 测试调用 registry 包级生命周期入口。 */
public final class ScheduleCapabilityRegistryTestAccess {

    private ScheduleCapabilityRegistryTestAccess() {
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

    public static Optional<ScheduleGenerationDrain> withdraw(
            ScheduleCapabilityRegistry registry, ScheduleCapabilityPublication publication) {
        return registry.withdraw(publication);
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
