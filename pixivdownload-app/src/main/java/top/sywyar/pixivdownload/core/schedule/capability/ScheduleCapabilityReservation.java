package top.sywyar.pixivdownload.core.schedule.capability;

/**
 * 一次尚未对读者可见的计划任务能力发布预留。
 *
 * <p>这是一个只能由同包 registry 创建的对象身份句柄，不是可复制的值。行为 Bean 保留在 registry 的短生命
 * 预留项中，迁移成功后由同一实例原子提交，失败时必须释放。
 */
public final class ScheduleCapabilityReservation {

    private final ScheduleCapabilityOwner owner;
    private final long reservationId;

    ScheduleCapabilityReservation(ScheduleCapabilityOwner owner, long reservationId) {
        if (owner == null) {
            throw new IllegalArgumentException("reservation owner must not be null");
        }
        if (reservationId <= 0) {
            throw new IllegalArgumentException("reservation id must be positive");
        }
        this.owner = owner;
        this.reservationId = reservationId;
    }

    ScheduleCapabilityOwner owner() {
        return owner;
    }

    long reservationId() {
        return reservationId;
    }

    @Override
    public String toString() {
        return "ScheduleCapabilityReservation[opaque]";
    }
}
