package top.sywyar.pixivdownload.core.schedule.capability;

import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** 来源 planning 的短租约；由调用方先持有对象，再在 try/finally 内激活。 */
public final class SchedulePlanningLease implements AutoCloseable {

    record TransferredSource(
            ScheduledSourceDescriptor descriptor,
            ScheduledSourceExecutor sourceExecutor,
            ScheduledSourceProvider legacySourceProvider
    ) {
    }

    private final ScheduleCapabilityOwner owner;
    private final long publicationId;
    private final String activationToken;
    private final String sourceType;
    private final ScheduleLeaseState leaseState;
    private final ScheduleLeaseRoot root;
    private final ScheduleLeaseState.LeaseToken leaseToken;
    private TransferredSource source;

    SchedulePlanningLease(
            ScheduleCapabilityOwner owner,
            long publicationId,
            String activationToken,
            String sourceType,
            ScheduleLeaseState leaseState,
            ScheduleLeaseRoot root,
            ScheduleLeaseState.LeaseToken leaseToken,
            ScheduledSourceDescriptor descriptor,
            ScheduledSourceExecutor sourceExecutor,
            ScheduledSourceProvider legacySourceProvider) {
        this.owner = owner;
        this.publicationId = publicationId;
        this.activationToken = Objects.requireNonNull(activationToken, "activationToken");
        this.sourceType = sourceType;
        this.leaseState = leaseState;
        this.root = Objects.requireNonNull(root, "root");
        this.leaseToken = Objects.requireNonNull(leaseToken, "leaseToken");
        this.source = new TransferredSource(descriptor, sourceExecutor, legacySourceProvider);
    }

    public ScheduleCapabilityOwner owner() {
        return owner;
    }

    public long publicationId() {
        return publicationId;
    }

    public String activationToken() {
        return activationToken;
    }

    public String sourceType() {
        return sourceType;
    }

    public synchronized Optional<ScheduledSourceDescriptor> descriptor() {
        ensurePlanning();
        return Optional.ofNullable(source.descriptor());
    }

    public synchronized Optional<ScheduledSourceExecutor> sourceExecutor() {
        ensurePlanning();
        return Optional.ofNullable(source.sourceExecutor());
    }

    public synchronized Optional<ScheduledSourceProvider> legacySourceProvider() {
        ensurePlanning();
        return Optional.ofNullable(source.legacySourceProvider());
    }

    public synchronized ScheduledCancellation cancellation() {
        ensurePlanning();
        return root.cancellation();
    }

    public boolean isActive() {
        return root.isPlanning();
    }

    synchronized <T> Optional<T> whileActive(Supplier<Optional<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        if (!root.isPlanning() || source == null) {
            return Optional.empty();
        }
        return Objects.requireNonNull(operation.get(), "operation result");
    }

    synchronized TransferredSource prepareTransfer() {
        ensurePlanning();
        return source;
    }

    synchronized void finishTransfer(TransferredSource transferred) {
        Objects.requireNonNull(transferred, "transferred source");
        if (transferred != source || !root.isExecution()) {
            throw new IllegalStateException("schedule planning transfer no longer matches source");
        }
        source = null;
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
        boolean ownedRoot = root.closePlanningOrPrepared();
        Throwable failure = null;
        try {
            if (ownedRoot || root.isClosed()) {
                leaseState.release(leaseToken);
            }
        } catch (Throwable releaseFailure) {
            failure = releaseFailure;
        } finally {
            source = null;
        }
        ScheduleSingleCapabilityLease.rethrow(failure);
    }

    private void ensurePlanning() {
        if (!root.isPlanning() || source == null) {
            throw new IllegalStateException("schedule planning lease is not active");
        }
    }
}
