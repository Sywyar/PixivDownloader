package top.sywyar.pixivdownload.core.schedule.capability;

import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.util.Optional;

/** 仅供 app 测试调用 registry 包级生命周期入口与构造伪造身份；生产代码没有对应入口。 */
public final class ScheduleCapabilityRegistryTestAccess {

    private ScheduleCapabilityRegistryTestAccess() {
    }

    public static ScheduleCapabilityReservation reserve(
            ScheduleCapabilityRegistry registry, ScheduleOwnerBundle bundle) {
        return registry.reserve(bundle);
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
        return registry.publish(bundle);
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
        return new PluginScheduleContributionRegistrar(registry, migrationService, pluginRegistry);
    }

    public static Optional<ScheduleCapabilityPublication> publication(
            PluginScheduleContributionRegistrar registrar,
            ScheduleCapabilityOwner owner) {
        return registrar.publication(owner);
    }
}
