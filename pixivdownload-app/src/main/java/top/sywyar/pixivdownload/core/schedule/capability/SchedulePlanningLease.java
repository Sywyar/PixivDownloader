package top.sywyar.pixivdownload.core.schedule.capability;

import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 来源 planning 的短租约。调用来源行为并取得纯数据执行计划后，应由 registry 原子扩展为执行租约。
 */
public final class SchedulePlanningLease implements AutoCloseable {

    record TransferredSource(
            ScheduledSourceDescriptor descriptor,
            ScheduledSourceExecutor sourceExecutor,
            ScheduledSourceProvider legacySourceProvider
    ) {
    }

    private final ScheduleCapabilityOwner owner;
    private final long publicationId;
    private final String sourceType;
    private final ScheduleLeaseState leaseState;
    private final ScheduleLeaseState.CancellationSignal cancellation;

    private ScheduledSourceDescriptor descriptor;
    private ScheduledSourceExecutor sourceExecutor;
    private ScheduledSourceProvider legacySourceProvider;
    private boolean active = true;

    SchedulePlanningLease(
            ScheduleCapabilityOwner owner,
            long publicationId,
            String sourceType,
            ScheduleLeaseState leaseState,
            ScheduleLeaseState.CancellationSignal cancellation,
            ScheduledSourceDescriptor descriptor,
            ScheduledSourceExecutor sourceExecutor,
            ScheduledSourceProvider legacySourceProvider) {
        this.owner = owner;
        this.publicationId = publicationId;
        this.sourceType = sourceType;
        this.leaseState = leaseState;
        this.cancellation = cancellation;
        this.descriptor = descriptor;
        this.sourceExecutor = sourceExecutor;
        this.legacySourceProvider = legacySourceProvider;
    }

    public ScheduleCapabilityOwner owner() {
        return owner;
    }

    public long publicationId() {
        return publicationId;
    }

    public String sourceType() {
        return sourceType;
    }

    public synchronized Optional<ScheduledSourceDescriptor> descriptor() {
        ensureActive();
        return Optional.ofNullable(descriptor);
    }

    public synchronized Optional<ScheduledSourceExecutor> sourceExecutor() {
        ensureActive();
        return Optional.ofNullable(sourceExecutor);
    }

    public synchronized Optional<ScheduledSourceProvider> legacySourceProvider() {
        ensureActive();
        return Optional.ofNullable(legacySourceProvider);
    }

    public synchronized ScheduledCancellation cancellation() {
        ensureActive();
        return cancellation;
    }

    public synchronized boolean isActive() {
        return active;
    }

    /**
     * 在 close 无法插入的同一临界区内完成一次活动租约操作。
     * registry 用它把状态检查、来源读取、其它 owner 租约获取与 transfer 组成一个线性化操作。
     */
    synchronized <T> Optional<T> whileActive(Supplier<Optional<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        if (!active) {
            return Optional.empty();
        }
        return Objects.requireNonNull(operation.get(), "operation result");
    }

    synchronized TransferredSource transfer() {
        ensureActive();
        TransferredSource transferred = new TransferredSource(descriptor, sourceExecutor, legacySourceProvider);
        descriptor = null;
        sourceExecutor = null;
        legacySourceProvider = null;
        active = false;
        return transferred;
    }

    ScheduleLeaseState leaseState() {
        return leaseState;
    }

    ScheduleLeaseState.CancellationSignal cancellationSignal() {
        return cancellation;
    }

    @Override
    public void close() {
        boolean release;
        synchronized (this) {
            release = active;
            if (active) {
                descriptor = null;
                sourceExecutor = null;
                legacySourceProvider = null;
                active = false;
            }
        }
        if (release) {
            leaseState.release(cancellation);
        }
    }

    private void ensureActive() {
        if (!active) {
            throw new IllegalStateException("schedule planning lease is closed or transferred");
        }
    }
}
