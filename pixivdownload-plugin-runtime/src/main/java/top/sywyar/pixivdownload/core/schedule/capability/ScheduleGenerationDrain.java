package top.sywyar.pixivdownload.core.schedule.capability;

/** 已撤回 publication 的归零凭据；不持有 owner bundle 或插件行为对象。 */
public final class ScheduleGenerationDrain {

    private final ScheduleCapabilityOwner owner;
    private final long publicationId;
    private final ScheduleLeaseState leaseState;

    ScheduleGenerationDrain(ScheduleCapabilityOwner owner, long publicationId,
                            ScheduleLeaseState leaseState) {
        this.owner = owner;
        this.publicationId = publicationId;
        this.leaseState = leaseState;
    }

    public ScheduleCapabilityOwner owner() {
        return owner;
    }

    public long publicationId() {
        return publicationId;
    }

    public boolean awaitDrained(long deadlineNanos) {
        return leaseState.awaitDrained(deadlineNanos);
    }

    public boolean awaitDrained() {
        return leaseState.awaitDrained();
    }

    public boolean isDrained() {
        return leaseState.isDrained();
    }

    public int activeLeaseCount() {
        return leaseState.activeLeaseCount();
    }
}
