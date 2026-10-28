package top.sywyar.pixivdownload.core.schedule.capability;

/** 仅供插件装配测试调用真实 registry 的包级发布入口。 */
public final class WorkbenchScheduleTestAccess {
    private WorkbenchScheduleTestAccess() {}
    public static void publish(ScheduleCapabilityRegistry registry, ScheduleOwnerBundle bundle) {
        var reservation = registry.allocateReservation(bundle.owner());
        try {
            registry.reserve(reservation, bundle);
            registry.commit(reservation);
        } catch (RuntimeException | Error failure) {
            registry.rollback(reservation);
            throw failure;
        }
    }
}
