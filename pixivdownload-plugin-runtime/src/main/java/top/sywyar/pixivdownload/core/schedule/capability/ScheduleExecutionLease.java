package top.sywyar.pixivdownload.core.schedule.capability;

import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 一次调度执行的复合租约；所有 owner 由一个根状态和一个附加 owner 分支共同控制。 */
public final class ScheduleExecutionLease implements AutoCloseable {

    record OwnerState(
            ScheduleCapabilityOwner owner,
            long publicationId,
            ScheduleLeaseState state,
            ScheduleLeaseState.LeaseToken leaseToken) {
    }

    private final SchedulePlanningLease planning;
    private final ScheduleLeaseRoot root;
    private final ScheduleLeaseBranch branch;
    private final OwnerState sourceOwnerState;
    private final List<OwnerState> additionalOwnerStates;
    private final String sourceType;
    private final Set<ScheduleCapabilityOwner> owners;

    private ScheduledSourceDescriptor descriptor;
    private ScheduledSourceExecutor sourceExecutor;
    private Map<String, ScheduledWorkExecutor> workExecutors;
    private Map<String, ScheduleCapabilityOwner> workExecutorOwners;
    private ScheduledCredentialPolicy credentialPolicy;
    private ScheduleCapabilityOwner credentialPolicyOwner;
    private Map<String, ScheduledExecutionGuard> guards;
    private Map<String, ScheduleCapabilityOwner> guardOwners;

    ScheduleExecutionLease(
            SchedulePlanningLease planning,
            ScheduleLeaseBranch branch,
            OwnerState sourceOwnerState,
            List<OwnerState> additionalOwnerStates,
            SchedulePlanningLease.TransferredSource source,
            Map<String, ScheduledWorkExecutor> workExecutors,
            Map<String, ScheduleCapabilityOwner> workExecutorOwners,
            ScheduledCredentialPolicy credentialPolicy,
            ScheduleCapabilityOwner credentialPolicyOwner,
            Map<String, ScheduledExecutionGuard> guards,
            Map<String, ScheduleCapabilityOwner> guardOwners) {
        this.planning = planning;
        this.root = planning.root();
        this.branch = branch;
        this.sourceOwnerState = sourceOwnerState;
        this.additionalOwnerStates = List.copyOf(additionalOwnerStates);
        this.sourceType = planning.sourceType();
        java.util.LinkedHashSet<ScheduleCapabilityOwner> ownerSet = new java.util.LinkedHashSet<>();
        ownerSet.add(sourceOwnerState.owner());
        additionalOwnerStates.forEach(owner -> ownerSet.add(owner.owner()));
        this.owners = Set.copyOf(ownerSet);
        this.descriptor = source.descriptor();
        this.sourceExecutor = source.sourceExecutor();
        this.workExecutors = Map.copyOf(workExecutors);
        this.workExecutorOwners = Map.copyOf(workExecutorOwners);
        this.credentialPolicy = credentialPolicy;
        this.credentialPolicyOwner = credentialPolicyOwner;
        this.guards = Map.copyOf(guards);
        this.guardOwners = Map.copyOf(guardOwners);
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

    public synchronized Optional<ScheduledWorkExecutor> workExecutor(String workType) {
        ensureActive();
        return Optional.ofNullable(workExecutors.get(workType));
    }

    public synchronized Map<String, ScheduledWorkExecutor> workExecutors() {
        ensureActive();
        return Map.copyOf(workExecutors);
    }

    public synchronized Optional<ScheduleCapabilityOwner> workExecutorOwner(String workType) {
        ensureActive();
        return Optional.ofNullable(workExecutorOwners.get(workType));
    }

    public synchronized Map<String, ScheduleCapabilityOwner> workExecutorOwners() {
        ensureActive();
        return Map.copyOf(workExecutorOwners);
    }

    public synchronized Optional<ScheduledCredentialPolicy> credentialPolicy() {
        ensureActive();
        return Optional.ofNullable(credentialPolicy);
    }

    public synchronized Optional<ScheduleCapabilityOwner> credentialPolicyOwner() {
        ensureActive();
        return Optional.ofNullable(credentialPolicyOwner);
    }

    public synchronized Optional<ScheduledExecutionGuard> guard(String guardId) {
        ensureActive();
        return Optional.ofNullable(guards.get(guardId));
    }

    public synchronized Map<String, ScheduledExecutionGuard> guards() {
        ensureActive();
        return Map.copyOf(guards);
    }

    public synchronized Optional<ScheduleCapabilityOwner> guardOwner(String guardId) {
        ensureActive();
        return Optional.ofNullable(guardOwners.get(guardId));
    }

    public synchronized Map<String, ScheduleCapabilityOwner> guardOwners() {
        ensureActive();
        return Map.copyOf(guardOwners);
    }

    public synchronized ScheduledCancellation cancellation() {
        ensureActive();
        return root.cancellation();
    }

    public boolean isActive() {
        return root.isExecution();
    }

    SchedulePlanningLease planning() {
        return planning;
    }

    ScheduleLeaseBranch branch() {
        return branch;
    }

    OwnerState sourceOwnerState() {
        return sourceOwnerState;
    }

    List<OwnerState> additionalOwnerStates() {
        return additionalOwnerStates;
    }

    @Override
    public synchronized void close() {
        boolean closedExecution = root.closeExecution();
        branch.close();
        Throwable failure = null;
        try {
            for (OwnerState ownerState : additionalOwnerStates) {
                try {
                    ownerState.state().release(ownerState.leaseToken());
                } catch (Throwable releaseFailure) {
                    failure = preferred(failure, releaseFailure);
                }
            }
            if (closedExecution || root.isClosed()) {
                try {
                    sourceOwnerState.state().release(sourceOwnerState.leaseToken());
                } catch (Throwable releaseFailure) {
                    failure = preferred(failure, releaseFailure);
                }
            }
        } finally {
            descriptor = null;
            sourceExecutor = null;
            workExecutors = Map.of();
            workExecutorOwners = Map.of();
            credentialPolicy = null;
            credentialPolicyOwner = null;
            guards = Map.of();
            guardOwners = Map.of();
        }
        ScheduleSingleCapabilityLease.rethrow(failure);
    }

    private static Throwable preferred(Throwable current, Throwable candidate) {
        if (current == null || (!(current instanceof VirtualMachineError)
                && !(current instanceof ThreadDeath)
                && (candidate instanceof VirtualMachineError || candidate instanceof ThreadDeath))) {
            return candidate;
        }
        return current;
    }

    private void ensureActive() {
        if (!root.isExecution()) {
            throw new IllegalStateException("schedule execution lease is not active");
        }
    }
}
