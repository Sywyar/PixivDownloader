package top.sywyar.pixivdownload.core.schedule.capability;

import java.util.Objects;

/**
 * 不含插件 Bean 或 classloader 的纯值解析句柄。只有成功取得 lease 后才能读取它代表的行为对象。
 */
public final class ScheduleCapabilityHandle<T> {

    enum Kind {
        OWNER,
        SOURCE_DESCRIPTOR,
        SOURCE_EXECUTOR,
        WORK_EXECUTOR,
        CREDENTIAL_POLICY,
        EXECUTION_GUARD
    }

    private final Kind kind;
    private final String capabilityId;
    private final ScheduleCapabilityOwner owner;
    private final long publicationId;

    ScheduleCapabilityHandle(Kind kind, String capabilityId,
                             ScheduleCapabilityOwner owner, long publicationId) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.publicationId = publicationId;
    }

    public String capabilityId() {
        return capabilityId;
    }

    public ScheduleCapabilityOwner owner() {
        return owner;
    }

    public long publicationId() {
        return publicationId;
    }

    Kind kind() {
        return kind;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ScheduleCapabilityHandle<?> that)) {
            return false;
        }
        return publicationId == that.publicationId
                && kind == that.kind
                && capabilityId.equals(that.capabilityId)
                && owner.equals(that.owner);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, capabilityId, owner, publicationId);
    }

    @Override
    public String toString() {
        return "ScheduleCapabilityHandle[" + kind + ":" + capabilityId
                + ", owner=" + owner + ", publication=" + publicationId + "]";
    }
}
