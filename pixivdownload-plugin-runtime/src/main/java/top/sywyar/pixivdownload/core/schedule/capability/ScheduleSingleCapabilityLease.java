package top.sywyar.pixivdownload.core.schedule.capability;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;

import java.util.Objects;

/** 短时读取单个行为能力的租约；由调用方先持有对象，再在 try/finally 内激活。 */
public final class ScheduleSingleCapabilityLease<T> implements AutoCloseable {

    private final ScheduleCapabilityHandle<T> handle;
    private final ScheduleLeaseState leaseState;
    private final ScheduleLeaseRoot root;
    private final ScheduleLeaseState.LeaseToken leaseToken;
    private T capability;

    ScheduleSingleCapabilityLease(
            ScheduleCapabilityHandle<T> handle,
            ScheduleLeaseState leaseState,
            ScheduleLeaseRoot root,
            ScheduleLeaseState.LeaseToken leaseToken,
            T capability) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.leaseState = Objects.requireNonNull(leaseState, "leaseState");
        this.root = Objects.requireNonNull(root, "root");
        this.leaseToken = Objects.requireNonNull(leaseToken, "leaseToken");
        this.capability = Objects.requireNonNull(capability, "capability");
    }

    public ScheduleCapabilityHandle<T> handle() {
        return handle;
    }

    public ScheduleCapabilityOwner owner() {
        return handle.owner();
    }

    public synchronized T capability() {
        ensureActive();
        return capability;
    }

    public synchronized ScheduledCancellation cancellation() {
        ensureActive();
        return root.cancellation();
    }

    public boolean isActive() {
        return root.isSingle();
    }

    ScheduleLeaseState leaseState() {
        return leaseState;
    }

    ScheduleLeaseRoot root() {
        return root;
    }

    ScheduleLeaseState.LeaseToken leaseToken() {
        return leaseToken;
    }

    @Override
    public synchronized void close() {
        root.closeSingleOrPrepared();
        Throwable failure = null;
        try {
            leaseState.release(leaseToken);
        } catch (Throwable releaseFailure) {
            failure = releaseFailure;
        } finally {
            capability = null;
        }
        rethrow(failure);
    }

    private void ensureActive() {
        if (!root.isSingle()) {
            throw new IllegalStateException("schedule capability lease is not active");
        }
    }

    static void rethrow(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("schedule capability lease cleanup failed", failure);
    }
}
