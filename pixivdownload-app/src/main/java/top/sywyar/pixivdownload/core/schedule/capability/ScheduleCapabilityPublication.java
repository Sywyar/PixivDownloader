package top.sywyar.pixivdownload.core.schedule.capability;

/** 精确标识一次 owner 全量发布；同 generation 的 context 重建也会获得不同 publicationId。 */
public final class ScheduleCapabilityPublication {

    private final ScheduleCapabilityOwner owner;
    private final long publicationId;

    ScheduleCapabilityPublication(ScheduleCapabilityOwner owner, long publicationId) {
        this.owner = owner;
        this.publicationId = publicationId;
    }

    public ScheduleCapabilityOwner owner() {
        return owner;
    }

    public long publicationId() {
        return publicationId;
    }
}
