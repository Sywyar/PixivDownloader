package top.sywyar.pixivdownload.core.schedule.capability;

/**
 * 一次尚未对读者可见的计划任务能力发布预留。
 *
 * <p>这是一个只能由同包 registry 创建的对象身份句柄，不是可复制的值。行为 Bean 保留在 registry 的短生命
 * 预留项中，迁移成功后由同一实例原子提交，失败时必须释放。
 */
public final class ScheduleCapabilityReservation {

    static final class CommitBinding {
        private final ScheduleCapabilityPublication publication;
        private final String activationToken;
        private final ScheduleLeaseState leaseState;
        private final ScheduleGenerationDrain drain;

        private CommitBinding(
                ScheduleCapabilityPublication publication,
                String activationToken,
                ScheduleLeaseState leaseState) {
            this.publication = publication;
            this.activationToken = activationToken;
            this.leaseState = leaseState;
            this.drain = new ScheduleGenerationDrain(
                    publication.owner(), publication.publicationId(), leaseState);
        }

        ScheduleCapabilityPublication publication() {
            return publication;
        }

        String activationToken() {
            return activationToken;
        }

        ScheduleLeaseState leaseState() {
            return leaseState;
        }

        ScheduleGenerationDrain drain() {
            return drain;
        }
    }

    private final ScheduleCapabilityOwner owner;
    private final long reservationId;
    private CommitBinding commitBinding;

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

    void bindCommit(
            ScheduleCapabilityPublication publication,
            String activationToken,
            ScheduleLeaseState leaseState) {
        if (commitBinding != null) {
            throw new IllegalStateException("schedule reservation already has a commit binding");
        }
        CommitBinding binding = new CommitBinding(publication, activationToken, leaseState);
        commitBinding = binding;
    }

    CommitBinding commitBinding() {
        return commitBinding;
    }

    @Override
    public String toString() {
        return "ScheduleCapabilityReservation[opaque]";
    }
}
