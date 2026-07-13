package top.sywyar.pixivdownload.core.schedule.capability;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;

import java.util.Objects;

/** 短时读取单个行为能力的租约；close 后清除行为对象引用。 */
public final class ScheduleSingleCapabilityLease<T> implements AutoCloseable {

    private final ScheduleCapabilityHandle<T> handle;
    private final ScheduleLeaseState leaseState;
    private final ScheduleLeaseState.CancellationSignal cancellation;
    private T capability;
    private boolean active = true;

    ScheduleSingleCapabilityLease(
            ScheduleCapabilityHandle<T> handle,
            ScheduleLeaseState leaseState,
            ScheduleLeaseState.CancellationSignal cancellation,
            T capability) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.leaseState = Objects.requireNonNull(leaseState, "leaseState");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
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
        return cancellation;
    }

    public synchronized boolean isActive() {
        return active;
    }

    @Override
    public void close() {
        boolean release;
        synchronized (this) {
            release = active;
            capability = null;
            active = false;
        }
        if (release) {
            leaseState.release(cancellation);
        }
    }

    private void ensureActive() {
        if (!active) {
            throw new IllegalStateException("schedule capability lease is closed");
        }
    }
}
