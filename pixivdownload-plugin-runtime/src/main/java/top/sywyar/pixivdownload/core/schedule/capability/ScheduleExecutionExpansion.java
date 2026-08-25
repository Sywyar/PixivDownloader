package top.sywyar.pixivdownload.core.schedule.capability;

import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityIndex.CapabilityEntry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityIndex.PublishedOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityIndex.WorkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** 根据来源执行计划解析全部依赖能力并组装复合租约。 */
final class ScheduleExecutionExpansion {

    private ScheduleExecutionExpansion() {
    }

    static Optional<ScheduleExecutionLease> prepare(
            ScheduleCapabilityIndex current,
            SchedulePlanningLease planning,
            Set<String> workTypes,
            String credentialPolicyId,
            Set<String> guardIds,
            Predicate<String> ownerAdmission) {
        PublishedOwner sourceOwner = current.currentPublishedOwner(
                planning.owner(), planning.publicationId(), ownerAdmission);
        if (sourceOwner == null
                || sourceOwner.leaseState() != planning.leaseState()
                || !sourceOwner.leaseState().isAccepting()) {
            return Optional.empty();
        }

        ScheduledSourceDescriptor descriptor = planning.descriptor().orElse(null);
        if (descriptor == null || planning.sourceExecutor().isEmpty()) {
            return Optional.empty();
        }
        if (!descriptor.possibleWorkTypes().containsAll(workTypes)) {
            throw new IllegalArgumentException("execution plan requests undeclared work type for source: "
                    + planning.sourceType());
        }
        if (credentialPolicyId != null
                && !descriptor.credentialPolicyIds().contains(credentialPolicyId)) {
            throw new IllegalArgumentException(
                    "execution plan requests undeclared credential policy for source: "
                            + planning.sourceType());
        }
        if (!descriptor.guardIds().containsAll(guardIds)) {
            throw new IllegalArgumentException("execution plan requests undeclared guard for source: "
                    + planning.sourceType());
        }

        Map<ScheduleCapabilityOwner, PublishedOwner> requiredOwners = new LinkedHashMap<>();
        requiredOwners.put(planning.owner(), sourceOwner);
        Map<String, ScheduledWorkExecutor> workExecutors = new LinkedHashMap<>();
        Map<String, ScheduleCapabilityOwner> workExecutorOwners = new LinkedHashMap<>();
        Map<String, Long> workExecutorPublicationIds = new LinkedHashMap<>();
        for (String workType : workTypes) {
            WorkRoute route = current.worksByType().get(workType);
            if (route == null) {
                return Optional.empty();
            }
            PublishedOwner published = current.currentPublishedOwner(
                    route.owner(), route.publicationId(), ownerAdmission);
            if (published == null) {
                return Optional.empty();
            }
            requiredOwners.put(route.owner(), published);
            workExecutors.put(workType, route.executor());
            workExecutorOwners.put(workType, route.owner());
            workExecutorPublicationIds.put(workType, route.publicationId());
        }

        ScheduledCredentialPolicy credentialPolicy = null;
        ScheduleCapabilityOwner credentialPolicyOwner = null;
        if (credentialPolicyId != null) {
            CapabilityEntry<ScheduledCredentialPolicy> entry =
                    current.credentialPolicies().get(credentialPolicyId);
            if (entry == null) {
                return Optional.empty();
            }
            PublishedOwner published = current.currentPublishedOwner(
                    entry.owner(), entry.publicationId(), ownerAdmission);
            if (published == null) {
                return Optional.empty();
            }
            requiredOwners.put(entry.owner(), published);
            credentialPolicy = entry.capability();
            credentialPolicyOwner = entry.owner();
        }

        Map<String, ScheduledExecutionGuard> guards = new LinkedHashMap<>();
        Map<String, ScheduleCapabilityOwner> guardOwners = new LinkedHashMap<>();
        for (String guardId : guardIds) {
            CapabilityEntry<ScheduledExecutionGuard> entry = current.guards().get(guardId);
            if (entry == null) {
                return Optional.empty();
            }
            PublishedOwner published = current.currentPublishedOwner(
                    entry.owner(), entry.publicationId(), ownerAdmission);
            if (published == null) {
                return Optional.empty();
            }
            requiredOwners.put(entry.owner(), published);
            guards.put(guardId, entry.capability());
            guardOwners.put(guardId, entry.owner());
        }

        SchedulePlanningLease.TransferredSource source = planning.prepareTransfer();
        ScheduleLeaseBranch branch = new ScheduleLeaseBranch(planning.root());
        ScheduleExecutionLease.OwnerState sourceState = new ScheduleExecutionLease.OwnerState(
                planning.owner(),
                planning.publicationId(),
                sourceOwner.leaseState(),
                planning.leaseToken());
        List<ScheduleExecutionLease.OwnerState> additionalOwners =
                new ArrayList<>(requiredOwners.size() - 1);
        for (Map.Entry<ScheduleCapabilityOwner, PublishedOwner> required : requiredOwners.entrySet()) {
            if (required.getKey().equals(planning.owner())) {
                continue;
            }
            additionalOwners.add(new ScheduleExecutionLease.OwnerState(
                    required.getKey(),
                    required.getValue().publicationId(),
                    required.getValue().leaseState(),
                    ScheduleLeaseState.LeaseToken.branch(planning.root(), branch)));
        }

        return Optional.of(new ScheduleExecutionLease(
                planning,
                branch,
                sourceState,
                additionalOwners,
                source,
                workExecutors,
                workExecutorOwners,
                workExecutorPublicationIds,
                credentialPolicy,
                credentialPolicyOwner,
                guards,
                guardOwners));
    }
}
