package top.sywyar.pixivdownload.core.schedule.capability;

import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 一次调度执行所需全部 owner 的复合租约。任一 owner 撤回都会触发共享取消信号；close 后不再持插件 Bean。
 */
public final class ScheduleExecutionLease implements AutoCloseable {

    record OwnerState(ScheduleCapabilityOwner owner, ScheduleLeaseState state) {
    }

    private final String sourceType;
    private final ScheduleLeaseState.CancellationSignal cancellation;
    private final List<OwnerState> ownerStates;
    private final Set<ScheduleCapabilityOwner> owners;

    private ScheduledSourceDescriptor descriptor;
    private ScheduledSourceExecutor sourceExecutor;
    private ScheduledSourceProvider legacySourceProvider;
    private Map<String, ScheduledWorkExecutor> workExecutors;
    private Map<String, ScheduledWorkRunner> legacyWorkRunners;
    private ScheduledCredentialPolicy credentialPolicy;
    private Map<String, ScheduledExecutionGuard> guards;
    private boolean active = true;

    ScheduleExecutionLease(
            String sourceType,
            ScheduleLeaseState.CancellationSignal cancellation,
            List<OwnerState> ownerStates,
            SchedulePlanningLease.TransferredSource source,
            Map<String, ScheduledWorkExecutor> workExecutors,
            Map<String, ScheduledWorkRunner> legacyWorkRunners,
            ScheduledCredentialPolicy credentialPolicy,
            Map<String, ScheduledExecutionGuard> guards) {
        this.sourceType = sourceType;
        this.cancellation = cancellation;
        this.ownerStates = List.copyOf(ownerStates);
        this.owners = ownerStates.stream().map(OwnerState::owner)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.descriptor = source.descriptor();
        this.sourceExecutor = source.sourceExecutor();
        this.legacySourceProvider = source.legacySourceProvider();
        this.workExecutors = Map.copyOf(workExecutors);
        this.legacyWorkRunners = Map.copyOf(legacyWorkRunners);
        this.credentialPolicy = credentialPolicy;
        this.guards = Map.copyOf(guards);
    }

    public String sourceType() {
        return sourceType;
    }

    public Set<ScheduleCapabilityOwner> owners() {
        return owners;
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

    public synchronized Optional<ScheduledWorkExecutor> workExecutor(String workType) {
        ensureActive();
        return Optional.ofNullable(workExecutors.get(workType));
    }

    public synchronized Map<String, ScheduledWorkExecutor> workExecutors() {
        ensureActive();
        return Map.copyOf(workExecutors);
    }

    public synchronized Optional<ScheduledWorkRunner> legacyWorkRunner(String workType) {
        ensureActive();
        return Optional.ofNullable(legacyWorkRunners.get(workType));
    }

    public synchronized Map<String, ScheduledWorkRunner> legacyWorkRunners() {
        ensureActive();
        return Map.copyOf(legacyWorkRunners);
    }

    public synchronized Optional<ScheduledCredentialPolicy> credentialPolicy() {
        ensureActive();
        return Optional.ofNullable(credentialPolicy);
    }

    public synchronized Optional<ScheduledExecutionGuard> guard(String guardId) {
        ensureActive();
        return Optional.ofNullable(guards.get(guardId));
    }

    public synchronized Map<String, ScheduledExecutionGuard> guards() {
        ensureActive();
        return Map.copyOf(guards);
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
        List<OwnerState> releases = List.of();
        synchronized (this) {
            if (active) {
                descriptor = null;
                sourceExecutor = null;
                legacySourceProvider = null;
                workExecutors = Map.of();
                legacyWorkRunners = Map.of();
                credentialPolicy = null;
                guards = Map.of();
                active = false;
                releases = new ArrayList<>(ownerStates);
            }
        }
        for (OwnerState ownerState : releases) {
            ownerState.state().release(cancellation);
        }
    }

    private void ensureActive() {
        if (!active) {
            throw new IllegalStateException("schedule execution lease is closed");
        }
    }
}
