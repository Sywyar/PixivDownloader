package top.sywyar.pixivdownload.core.schedule.capability;

/**
 * 精确标识一次 owner 全量发布；同 generation 的 context 重建也会获得不同 publicationId。
 *
 * <p>这是由 registry 创建并按对象身份验证的不透明句柄。公开观察字段不构成 mutation 授权。
 */
public final class ScheduleCapabilityPublication {

    private final ScheduleCapabilityOwner owner;
    private final long publicationId;

    ScheduleCapabilityPublication(ScheduleCapabilityOwner owner, long publicationId) {
        if (owner == null) {
            throw new IllegalArgumentException("publication owner must not be null");
        }
        if (publicationId <= 0L) {
            throw new IllegalArgumentException("publication id must be positive");
        }
        this.owner = owner;
        this.publicationId = publicationId;
    }

    public ScheduleCapabilityOwner owner() {
        return owner;
    }

    public long publicationId() {
        return publicationId;
    }

    @Override
    public String toString() {
        return "ScheduleCapabilityPublication[owner=" + owner
                + ", publicationId=" + publicationId + "]";
    }
}
