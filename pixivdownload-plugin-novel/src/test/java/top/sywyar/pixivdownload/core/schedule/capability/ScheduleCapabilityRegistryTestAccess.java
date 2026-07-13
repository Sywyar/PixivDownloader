package top.sywyar.pixivdownload.core.schedule.capability;

import java.util.Optional;

/** 仅供 novel 测试调用 registry 包级生命周期入口。 */
public final class ScheduleCapabilityRegistryTestAccess {

    private ScheduleCapabilityRegistryTestAccess() {
    }

    public static ScheduleCapabilityPublication publish(
            ScheduleCapabilityRegistry registry, ScheduleOwnerBundle bundle) {
        return registry.publish(bundle);
    }

    public static Optional<ScheduleGenerationDrain> withdraw(
            ScheduleCapabilityRegistry registry, ScheduleCapabilityPublication publication) {
        return registry.withdraw(publication);
    }
}
