package top.sywyar.pixivdownload.core.schedule.capability;

import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** 计划能力的不可变路由索引与 owner 观测投影。 */
record ScheduleCapabilityIndex(
        long revision,
        Map<ScheduleCapabilityOwner, PublishedOwner> owners,
        Map<String, SourceRoute> sourcesByName,
        Map<String, SourceRoute> sourcesByCanonical,
        Map<String, WorkRoute> worksByType,
        Map<String, CapabilityEntry<ScheduledCredentialPolicy>> credentialPolicies,
        Map<String, CapabilityEntry<ScheduledExecutionGuard>> guards,
        ScheduleCapabilityRegistry.SnapshotView view
) {

    record PublishedOwner(
            ScheduleOwnerBundle bundle,
            long publicationId,
            String activationToken,
            ScheduleCapabilityPublication publication,
            ScheduleLeaseState leaseState,
            ScheduleGenerationDrain drain
    ) {
    }

    record SourceRoute(
            ScheduleCapabilityOwner owner,
            long publicationId,
            String sourceType,
            ScheduledSourceDescriptor descriptor,
            ScheduledSourceExecutor executor
    ) {
    }

    record WorkRoute(
            ScheduleCapabilityOwner owner,
            long publicationId,
            String workType,
            ScheduledWorkExecutor executor
    ) {
    }

    record CapabilityEntry<T>(
            ScheduleCapabilityOwner owner,
            long publicationId,
            String capabilityId,
            T capability
    ) {
    }

    static ScheduleCapabilityIndex empty(String epoch) {
        ScheduleCapabilityRegistry.SnapshotView view =
                new ScheduleCapabilityRegistry.SnapshotView(epoch, 0L, List.of());
        return new ScheduleCapabilityIndex(
                0L, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), view);
    }

    static ScheduleCapabilityIndex rebuild(
            String epoch,
            long revision,
            Map<ScheduleCapabilityOwner, PublishedOwner> mutableOwners) {
        Map<ScheduleCapabilityOwner, PublishedOwner> owners = Map.copyOf(mutableOwners);
        Map<String, SourceRoute> sourcesByName = new LinkedHashMap<>();
        Map<String, SourceRoute> sourcesByCanonical = new LinkedHashMap<>();
        Map<String, WorkRoute> works = new LinkedHashMap<>();
        Map<String, CapabilityEntry<ScheduledCredentialPolicy>> policies = new LinkedHashMap<>();
        Map<String, CapabilityEntry<ScheduledExecutionGuard>> guards = new LinkedHashMap<>();
        List<ScheduleCapabilityRegistry.OwnerView> ownerViews = new ArrayList<>();

        List<Map.Entry<ScheduleCapabilityOwner, PublishedOwner>> orderedOwners =
                new ArrayList<>(mutableOwners.entrySet());
        orderedOwners.sort(Map.Entry.comparingByKey());
        for (Map.Entry<ScheduleCapabilityOwner, PublishedOwner> ownerEntry : orderedOwners) {
            ScheduleCapabilityOwner owner = ownerEntry.getKey();
            PublishedOwner published = ownerEntry.getValue();
            ScheduleOwnerBundle bundle = published.bundle();

            Map<String, ScheduleOwnerBundle.SourceExecutorEntry> sourceExecutors = new LinkedHashMap<>();
            for (ScheduleOwnerBundle.SourceExecutorEntry executor : bundle.sourceExecutors()) {
                sourceExecutors.put(executor.sourceType(), executor);
            }

            Set<String> aliases = new LinkedHashSet<>();
            for (ScheduleOwnerBundle.SourceDescriptorEntry descriptor : bundle.sourceDescriptors()) {
                ScheduleOwnerBundle.SourceExecutorEntry executor = sourceExecutors.get(descriptor.sourceType());
                SourceRoute route = new SourceRoute(
                        owner,
                        published.publicationId(),
                        descriptor.sourceType(),
                        descriptor.descriptor(),
                        executor.executor());
                putUnique(
                        sourcesByCanonical,
                        descriptor.sourceType(),
                        route,
                        "scheduled source descriptor type");
                putUnique(sourcesByName, descriptor.sourceType(), route, "scheduled source name");
                for (String alias : descriptor.aliases()) {
                    aliases.add(alias);
                    putUnique(sourcesByName, alias, route, "scheduled source alias");
                }
            }

            for (ScheduleOwnerBundle.WorkExecutorEntry work : bundle.workExecutors()) {
                WorkRoute route = new WorkRoute(
                        owner, published.publicationId(), work.workType(), work.executor());
                putUnique(works, work.workType(), route, "scheduled work executor");
            }

            for (ScheduleOwnerBundle.CredentialPolicyEntry policy : bundle.credentialPolicies()) {
                CapabilityEntry<ScheduledCredentialPolicy> entry = new CapabilityEntry<>(
                        owner, published.publicationId(), policy.policyId(), policy.policy());
                putUnique(policies, policy.policyId(), entry, "scheduled credential policy");
            }
            for (ScheduleOwnerBundle.GuardEntry guard : bundle.guards()) {
                CapabilityEntry<ScheduledExecutionGuard> entry = new CapabilityEntry<>(
                        owner, published.publicationId(), guard.guardId(), guard.guard());
                putUnique(guards, guard.guardId(), entry, "scheduled execution guard");
            }

            ownerViews.add(new ScheduleCapabilityRegistry.OwnerView(
                    owner,
                    published.publicationId(),
                    published.activationToken(),
                    sortedSet(bundle.sourceDescriptors().stream()
                            .map(ScheduleOwnerBundle.SourceDescriptorEntry::sourceType).toList()),
                    sortedSet(aliases),
                    sortedSet(bundle.workExecutors().stream()
                            .map(ScheduleOwnerBundle.WorkExecutorEntry::workType).toList()),
                    sortedSet(bundle.credentialPolicies().stream()
                            .map(ScheduleOwnerBundle.CredentialPolicyEntry::policyId).toList()),
                    sortedSet(bundle.guards().stream()
                            .map(ScheduleOwnerBundle.GuardEntry::guardId).toList()),
                    bundle.sourceDescriptors().stream()
                            .map(ScheduleOwnerBundle.SourceDescriptorEntry::descriptor)
                            .sorted(Comparator.comparing(ScheduledSourceDescriptor::sourceType))
                            .toList()));
        }

        ScheduleCapabilityRegistry.SnapshotView view =
                new ScheduleCapabilityRegistry.SnapshotView(epoch, revision, ownerViews);
        return new ScheduleCapabilityIndex(
                revision,
                owners,
                Map.copyOf(sourcesByName),
                Map.copyOf(sourcesByCanonical),
                Map.copyOf(works),
                Map.copyOf(policies),
                Map.copyOf(guards),
                view);
    }

    static void rejectOwnerClash(
            Map<ScheduleCapabilityOwner, PublishedOwner> owners,
            ScheduleCapabilityOwner candidate) {
        for (ScheduleCapabilityOwner active : owners.keySet()) {
            if (active.featurePluginId().equals(candidate.featurePluginId())) {
                throw new IllegalStateException("schedule feature owner already published: "
                        + candidate.featurePluginId() + " (active: " + active + ")");
            }
            if (active.packageId().equals(candidate.packageId())) {
                throw new IllegalStateException("schedule package owner already published: "
                        + candidate.packageId() + " (active: " + active + ")");
            }
        }
    }

    PublishedOwner currentPublishedOwner(
            ScheduleCapabilityOwner owner,
            long publicationId,
            Predicate<String> ownerAdmission) {
        PublishedOwner published = owners.get(owner);
        if (published == null || published.publicationId() != publicationId
                || !published.leaseState().isAccepting()
                || !ownerAdmission.test(owner.featurePluginId())) {
            return null;
        }
        return published;
    }

    @SuppressWarnings("unchecked")
    <T> T resolveCapability(ScheduleCapabilityHandle<T> handle) {
        Object value = switch (handle.kind()) {
            case OWNER -> {
                PublishedOwner published = owners.get(handle.owner());
                yield published != null
                        && published.publicationId() == handle.publicationId()
                        && handle.capabilityId().equals(handle.owner().featurePluginId())
                        ? handle.owner() : null;
            }
            case SOURCE_DESCRIPTOR -> {
                SourceRoute route = sourcesByCanonical.get(handle.capabilityId());
                yield matches(route, handle) ? route.descriptor() : null;
            }
            case SOURCE_EXECUTOR -> {
                SourceRoute route = sourcesByCanonical.get(handle.capabilityId());
                yield matches(route, handle) ? route.executor() : null;
            }
            case WORK_EXECUTOR -> {
                WorkRoute route = worksByType.get(handle.capabilityId());
                yield matches(route, handle) ? route.executor() : null;
            }
            case CREDENTIAL_POLICY -> {
                CapabilityEntry<ScheduledCredentialPolicy> entry = credentialPolicies.get(handle.capabilityId());
                yield matches(entry, handle) ? entry.capability() : null;
            }
            case EXECUTION_GUARD -> {
                CapabilityEntry<ScheduledExecutionGuard> entry = guards.get(handle.capabilityId());
                yield matches(entry, handle) ? entry.capability() : null;
            }
        };
        return (T) value;
    }

    private static boolean matches(SourceRoute route, ScheduleCapabilityHandle<?> handle) {
        return route != null && route.owner().equals(handle.owner())
                && route.publicationId() == handle.publicationId();
    }

    private static boolean matches(WorkRoute route, ScheduleCapabilityHandle<?> handle) {
        return route != null && route.owner().equals(handle.owner())
                && route.publicationId() == handle.publicationId();
    }

    private static boolean matches(CapabilityEntry<?> entry, ScheduleCapabilityHandle<?> handle) {
        return entry != null && entry.owner().equals(handle.owner())
                && entry.publicationId() == handle.publicationId();
    }

    private static <T> void putUnique(Map<String, T> values, String key, T value, String label) {
        T previous = values.putIfAbsent(key, value);
        if (previous != null) {
            throw new IllegalStateException("duplicate " + label + ": " + key);
        }
    }

    private static Set<String> sortedSet(java.util.Collection<String> values) {
        return Set.copyOf(values.stream().sorted().toList());
    }
}
