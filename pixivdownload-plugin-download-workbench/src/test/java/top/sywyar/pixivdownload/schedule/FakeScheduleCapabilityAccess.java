package top.sywyar.pixivdownload.schedule;

import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityLease;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwnerSnapshot;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilitySnapshot;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleExecutionLease;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 计划宿主测试使用的稳定端口 fake。它复刻 prepare/activate、精确 publication、取消与 drain，
 * 不接触 runtime registry 的注册或包级测试入口。
 */
public final class FakeScheduleCapabilityAccess implements ScheduleCapabilityAccess {

    public record Publication(
            ScheduleCapabilityOwner owner,
            long publicationId,
            String activationToken
    ) {
    }

    public final class Drain {

        private final PublishedOwner published;

        private Drain(PublishedOwner published) {
            this.published = published;
        }

        public boolean isDrained() {
            synchronized (lock) {
                return published.activeLeases == 0;
            }
        }

        public int activeLeaseCount() {
            synchronized (lock) {
                return published.activeLeases;
            }
        }

        public boolean awaitDrained(long deadlineNanos) {
            synchronized (lock) {
                while (published.activeLeases != 0) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0L) {
                        return false;
                    }
                    long millis = remaining / 1_000_000L;
                    int nanos = (int) (remaining % 1_000_000L);
                    try {
                        lock.wait(millis, nanos);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return true;
            }
        }
    }

    private enum LeasePhase {
        PREPARED,
        ACTIVE,
        TRANSFERRED,
        CLOSED
    }

    private record SourceRoute(
            PublishedOwner published,
            ScheduledSourceDescriptor descriptor,
            ScheduledSourceExecutor executor
    ) {
    }

    private record WorkRoute(
            PublishedOwner published,
            ScheduledWorkExecutor executor
    ) {
    }

    private record PolicyRoute(
            PublishedOwner published,
            ScheduledCredentialPolicy policy
    ) {
    }

    private record GuardRoute(
            PublishedOwner published,
            ScheduledExecutionGuard guard
    ) {
    }

    private static final class PublishedOwner {

        private final ScheduleCapabilityOwner owner;
        private final long publicationId;
        private final String activationToken;
        private final List<ScheduledSourceDescriptor> descriptors;
        private final Map<String, ScheduledSourceExecutor> sourceExecutors;
        private final Map<String, ScheduledWorkExecutor> workExecutors;
        private final Map<String, ScheduledCredentialPolicy> credentialPolicies;
        private final Map<String, ScheduledExecutionGuard> guards;

        private boolean accepting = true;
        private volatile boolean cancellationRequested;
        private int activeLeases;

        private PublishedOwner(
                ScheduleCapabilityOwner owner,
                long publicationId,
                String activationToken,
                List<ScheduledSourceDescriptor> descriptors,
                Map<String, ScheduledSourceExecutor> sourceExecutors,
                Map<String, ScheduledWorkExecutor> workExecutors,
                Map<String, ScheduledCredentialPolicy> credentialPolicies,
                Map<String, ScheduledExecutionGuard> guards) {
            this.owner = owner;
            this.publicationId = publicationId;
            this.activationToken = activationToken;
            this.descriptors = descriptors;
            this.sourceExecutors = sourceExecutors;
            this.workExecutors = workExecutors;
            this.credentialPolicies = credentialPolicies;
            this.guards = guards;
        }
    }

    private final Object lock = new Object();
    private final String epoch = UUID.randomUUID().toString();
    private final Map<ScheduleCapabilityOwner, PublishedOwner> owners = new LinkedHashMap<>();
    private final Map<String, SourceRoute> sources = new LinkedHashMap<>();
    private final Map<String, WorkRoute> works = new LinkedHashMap<>();
    private final Map<String, PolicyRoute> policies = new LinkedHashMap<>();
    private final Map<String, GuardRoute> guards = new LinkedHashMap<>();
    private volatile ScheduleCapabilitySnapshot currentSnapshot =
            new ScheduleCapabilitySnapshot(epoch, 0L, List.of());

    private long revision;
    private long nextPublicationId;

    public Publication publish(
            ScheduleCapabilityOwner owner,
            List<? extends ScheduledSourceDescriptor> descriptors,
            List<? extends ScheduledSourceExecutor> sourceExecutors,
            List<? extends ScheduledWorkExecutor> workExecutors,
            List<? extends ScheduledCredentialPolicy> credentialPolicies,
            List<? extends ScheduledExecutionGuard> executionGuards) {
        Objects.requireNonNull(owner, "owner");
        Map<String, ScheduledSourceExecutor> sourceExecutorsByType = index(
                sourceExecutors, ScheduledSourceExecutor::sourceType, "source executor");
        Map<String, ScheduledWorkExecutor> workExecutorsByType = index(
                workExecutors, ScheduledWorkExecutor::workType, "work executor");
        Map<String, ScheduledCredentialPolicy> policiesById = index(
                credentialPolicies, ScheduledCredentialPolicy::policyId, "credential policy");
        Map<String, ScheduledExecutionGuard> guardsById = index(
                executionGuards, ScheduledExecutionGuard::guardId, "execution guard");
        List<ScheduledSourceDescriptor> descriptorValues = List.copyOf(descriptors);

        synchronized (lock) {
            if (owners.containsKey(owner)) {
                throw new IllegalStateException("schedule owner is already published: " + owner);
            }
            long publicationId = Math.incrementExact(nextPublicationId);
            PublishedOwner published = new PublishedOwner(
                    owner,
                    publicationId,
                    UUID.randomUUID().toString(),
                    descriptorValues,
                    sourceExecutorsByType,
                    workExecutorsByType,
                    policiesById,
                    guardsById);

            Map<String, SourceRoute> sourceClaims = new LinkedHashMap<>();
            for (ScheduledSourceDescriptor descriptor : descriptorValues) {
                Objects.requireNonNull(descriptor, "source descriptor");
                ScheduledSourceExecutor executor = sourceExecutorsByType.get(descriptor.sourceType());
                if (executor == null) {
                    throw new IllegalArgumentException(
                            "missing source executor for " + descriptor.sourceType());
                }
                putUnique(sourceClaims, descriptor.sourceType(),
                        new SourceRoute(published, descriptor, executor), "source");
                for (String alias : descriptor.legacyAliases()) {
                    putUnique(sourceClaims, alias,
                            new SourceRoute(published, descriptor, executor), "source alias");
                }
            }
            if (sourceExecutorsByType.size() != descriptorValues.size()) {
                throw new IllegalArgumentException("source descriptors and executors must match");
            }
            rejectClashes(sources, sourceClaims.keySet(), "source");
            rejectClashes(works, workExecutorsByType.keySet(), "work");
            rejectClashes(policies, policiesById.keySet(), "credential policy");
            rejectClashes(guards, guardsById.keySet(), "execution guard");

            owners.put(owner, published);
            sources.putAll(sourceClaims);
            workExecutorsByType.forEach((id, executor) ->
                    works.put(id, new WorkRoute(published, executor)));
            policiesById.forEach((id, policy) ->
                    policies.put(id, new PolicyRoute(published, policy)));
            guardsById.forEach((id, guard) ->
                    guards.put(id, new GuardRoute(published, guard)));
            nextPublicationId = publicationId;
            revision = Math.incrementExact(revision);
            publishSnapshot();
            return new Publication(owner, publicationId, published.activationToken);
        }
    }

    public Optional<Drain> withdraw(Publication publication) {
        if (publication == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            PublishedOwner published = owners.get(publication.owner());
            if (published == null
                    || published.publicationId != publication.publicationId()
                    || !published.activationToken.equals(publication.activationToken())) {
                return Optional.empty();
            }
            owners.remove(publication.owner());
            sources.entrySet().removeIf(entry -> entry.getValue().published == published);
            works.entrySet().removeIf(entry -> entry.getValue().published == published);
            policies.entrySet().removeIf(entry -> entry.getValue().published == published);
            guards.entrySet().removeIf(entry -> entry.getValue().published == published);
            published.accepting = false;
            published.cancellationRequested = true;
            revision = Math.incrementExact(revision);
            publishSnapshot();
            lock.notifyAll();
            return Optional.of(new Drain(published));
        }
    }

    @Override
    public ScheduleCapabilitySnapshot snapshot() {
        return currentSnapshot;
    }

    @Override
    public Optional<? extends ScheduleCapabilityLease<ScheduleCapabilityOwner>> prepareOwner(
            String featurePluginId) {
        if (featurePluginId == null || featurePluginId.isBlank()) {
            return Optional.empty();
        }
        synchronized (lock) {
            return owners.values().stream()
                    .filter(published -> published.owner.featurePluginId().equals(featurePluginId))
                    .sorted((left, right) -> left.owner.compareTo(right.owner))
                    .findFirst()
                    .map(published -> new SingleLease<>(published, published.owner));
        }
    }

    @Override
    public Optional<? extends ScheduleCapabilityLease<ScheduledWorkExecutor>> prepareWorkExecutor(
            String workType) {
        synchronized (lock) {
            WorkRoute route = works.get(workType);
            return route == null
                    ? Optional.empty()
                    : Optional.of(new SingleLease<>(route.published, route.executor));
        }
    }

    @Override
    public Optional<ScheduleCapabilityOwner> credentialPolicyOwner(String policyId) {
        synchronized (lock) {
            PolicyRoute route = policies.get(policyId);
            return route == null ? Optional.empty() : Optional.of(route.published.owner);
        }
    }

    @Override
    public Optional<? extends SchedulePlanningLease> prepareSource(String sourceTypeOrAlias) {
        synchronized (lock) {
            SourceRoute route = sources.get(sourceTypeOrAlias);
            return route == null ? Optional.empty() : Optional.of(new PlanningLease(route));
        }
    }

    @Override
    public boolean activate(ScheduleCapabilityLease<?> lease) {
        if (!(lease instanceof FakeScheduleCapabilityAccess.SingleLease<?> single)
                || single.access() != this) {
            return false;
        }
        synchronized (lock) {
            if (single.phase != LeasePhase.PREPARED || !isCurrent(single.published)) {
                return false;
            }
            single.phase = LeasePhase.ACTIVE;
            single.published.activeLeases++;
            return true;
        }
    }

    @Override
    public boolean activate(SchedulePlanningLease lease) {
        if (!(lease instanceof FakeScheduleCapabilityAccess.PlanningLease planning)
                || planning.access() != this) {
            return false;
        }
        synchronized (lock) {
            if (planning.phase != LeasePhase.PREPARED || !isCurrent(planning.published)
                    || sources.get(planning.sourceType) == null
                    || sources.get(planning.sourceType).published != planning.published) {
                return false;
            }
            planning.phase = LeasePhase.ACTIVE;
            planning.published.activeLeases++;
            return true;
        }
    }

    @Override
    public <T> Optional<T> whileCurrentPublication(
            SchedulePlanningLease lease,
            Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (!(lease instanceof FakeScheduleCapabilityAccess.PlanningLease planning)
                || planning.access() != this) {
            return Optional.empty();
        }
        synchronized (lock) {
            if (planning.phase != LeasePhase.ACTIVE || !isCurrent(planning.published)) {
                return Optional.empty();
            }
            SourceRoute route = sources.get(planning.sourceType);
            if (route == null || route.published != planning.published) {
                return Optional.empty();
            }
            return Optional.of(Objects.requireNonNull(
                    operation.get(), "current publication operation result"));
        }
    }

    @Override
    public Optional<? extends ScheduleExecutionLease> prepareExpansion(
            SchedulePlanningLease lease,
            ScheduledExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!(lease instanceof FakeScheduleCapabilityAccess.PlanningLease planning)
                || planning.access() != this) {
            return Optional.empty();
        }
        synchronized (lock) {
            if (planning.phase != LeasePhase.ACTIVE || !isCurrent(planning.published)) {
                return Optional.empty();
            }
            Set<String> workTypes = normalized(plan.requiredWorkTypes(), "required work type");
            Set<String> guardIds = new LinkedHashSet<>();
            for (ScheduledGuardBinding binding : plan.guards()) {
                if (binding == null || !guardIds.add(requireId(
                        binding.guardId(), "guard id"))) {
                    throw new IllegalArgumentException("duplicate or null guard binding");
                }
            }
            String policyId = plan.credentialPolicyId() == null
                    ? null
                    : requireId(plan.credentialPolicyId(), "credential policy id");
            ScheduledSourceDescriptor descriptor = planning.descriptor;
            if (!descriptor.possibleWorkTypes().containsAll(workTypes)) {
                throw new IllegalArgumentException(
                        "execution plan requests undeclared work type for source: "
                                + planning.sourceType);
            }
            if (policyId != null && !descriptor.credentialPolicyIds().contains(policyId)) {
                throw new IllegalArgumentException(
                        "execution plan requests undeclared credential policy for source: "
                                + planning.sourceType);
            }
            if (!descriptor.guardIds().containsAll(guardIds)) {
                throw new IllegalArgumentException(
                        "execution plan requests undeclared guard for source: "
                                + planning.sourceType);
            }

            Map<String, WorkRoute> requiredWorks = new LinkedHashMap<>();
            for (String workType : workTypes) {
                WorkRoute route = works.get(workType);
                if (route == null || !isCurrent(route.published)) {
                    return Optional.empty();
                }
                requiredWorks.put(workType, route);
            }
            PolicyRoute policy = policyId == null ? null : policies.get(policyId);
            if (policyId != null && (policy == null || !isCurrent(policy.published))) {
                return Optional.empty();
            }
            Map<String, GuardRoute> requiredGuards = new LinkedHashMap<>();
            for (String guardId : guardIds) {
                GuardRoute route = guards.get(guardId);
                if (route == null || !isCurrent(route.published)) {
                    return Optional.empty();
                }
                requiredGuards.put(guardId, route);
            }
            return Optional.of(new ExecutionLease(planning, requiredWorks, policy, requiredGuards));
        }
    }

    @Override
    public boolean activate(ScheduleExecutionLease lease) {
        if (!(lease instanceof FakeScheduleCapabilityAccess.ExecutionLease execution)
                || execution.access() != this) {
            return false;
        }
        synchronized (lock) {
            if (execution.phase != LeasePhase.PREPARED
                    || execution.planning.phase != LeasePhase.ACTIVE) {
                return false;
            }
            for (PublishedOwner published : execution.publishedOwners) {
                if (!isCurrent(published)) {
                    return false;
                }
            }
            for (PublishedOwner published : execution.additionalOwners) {
                published.activeLeases++;
            }
            execution.planning.phase = LeasePhase.TRANSFERRED;
            execution.phase = LeasePhase.ACTIVE;
            return true;
        }
    }

    private boolean isCurrent(PublishedOwner published) {
        return published.accepting && owners.get(published.owner) == published;
    }

    private void publishSnapshot() {
        List<ScheduleCapabilityOwnerSnapshot> ownerSnapshots = owners.values().stream()
                .sorted((left, right) -> left.owner.compareTo(right.owner))
                .map(published -> new ScheduleCapabilityOwnerSnapshot(
                        published.owner,
                        published.publicationId,
                        published.activationToken,
                        published.descriptors.stream()
                                .map(ScheduledSourceDescriptor::sourceType)
                                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                        published.descriptors.stream()
                                .flatMap(descriptor -> descriptor.legacyAliases().stream())
                                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                        published.workExecutors.keySet(),
                        published.credentialPolicies.keySet(),
                        published.guards.keySet(),
                        published.descriptors))
                .toList();
        currentSnapshot = new ScheduleCapabilitySnapshot(epoch, revision, ownerSnapshots);
    }

    private void release(PublishedOwner published) {
        if (published.activeLeases <= 0) {
            throw new IllegalStateException("schedule fake lease count underflow");
        }
        published.activeLeases--;
        lock.notifyAll();
    }

    private final class SingleLease<T> implements ScheduleCapabilityLease<T> {

        private final PublishedOwner published;
        private T capability;
        private LeasePhase phase = LeasePhase.PREPARED;

        private SingleLease(PublishedOwner published, T capability) {
            this.published = published;
            this.capability = capability;
        }

        private FakeScheduleCapabilityAccess access() {
            return FakeScheduleCapabilityAccess.this;
        }

        @Override
        public ScheduleCapabilityOwner owner() {
            return published.owner;
        }

        @Override
        public T capability() {
            synchronized (lock) {
                ensureActive();
                return capability;
            }
        }

        @Override
        public ScheduledCancellation cancellation() {
            synchronized (lock) {
                ensureActive();
                return () -> published.cancellationRequested;
            }
        }

        @Override
        public boolean isActive() {
            synchronized (lock) {
                return phase == LeasePhase.ACTIVE;
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (phase == LeasePhase.ACTIVE) {
                    release(published);
                }
                phase = LeasePhase.CLOSED;
                capability = null;
            }
        }

        private void ensureActive() {
            if (phase != LeasePhase.ACTIVE) {
                throw new IllegalStateException("schedule fake capability lease is not active");
            }
        }
    }

    private final class PlanningLease implements SchedulePlanningLease {

        private final PublishedOwner published;
        private final ScheduledSourceDescriptor descriptor;
        private final ScheduledSourceExecutor executor;
        private final String sourceType;
        private LeasePhase phase = LeasePhase.PREPARED;

        private PlanningLease(SourceRoute route) {
            this.published = route.published;
            this.descriptor = route.descriptor;
            this.executor = route.executor;
            this.sourceType = route.descriptor.sourceType();
        }

        private FakeScheduleCapabilityAccess access() {
            return FakeScheduleCapabilityAccess.this;
        }

        @Override
        public ScheduleCapabilityOwner owner() {
            return published.owner;
        }

        @Override
        public long publicationId() {
            return published.publicationId;
        }

        @Override
        public String activationToken() {
            return published.activationToken;
        }

        @Override
        public String sourceType() {
            return sourceType;
        }

        @Override
        public Optional<ScheduledSourceDescriptor> descriptor() {
            synchronized (lock) {
                ensureActive();
                return Optional.of(descriptor);
            }
        }

        @Override
        public Optional<ScheduledSourceExecutor> sourceExecutor() {
            synchronized (lock) {
                ensureActive();
                return Optional.of(executor);
            }
        }

        @Override
        public ScheduledCancellation cancellation() {
            synchronized (lock) {
                ensureActive();
                return () -> published.cancellationRequested;
            }
        }

        @Override
        public boolean isActive() {
            synchronized (lock) {
                return phase == LeasePhase.ACTIVE;
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (phase == LeasePhase.ACTIVE) {
                    release(published);
                }
                phase = LeasePhase.CLOSED;
            }
        }

        private void ensureActive() {
            if (phase != LeasePhase.ACTIVE) {
                throw new IllegalStateException("schedule fake planning lease is not active");
            }
        }
    }

    private final class ExecutionLease implements ScheduleExecutionLease {

        private final PlanningLease planning;
        private final Map<String, WorkRoute> workRoutes;
        private final PolicyRoute policyRoute;
        private final Map<String, GuardRoute> guardRoutes;
        private final List<PublishedOwner> publishedOwners;
        private final List<PublishedOwner> additionalOwners;
        private LeasePhase phase = LeasePhase.PREPARED;

        private ExecutionLease(
                PlanningLease planning,
                Map<String, WorkRoute> workRoutes,
                PolicyRoute policyRoute,
                Map<String, GuardRoute> guardRoutes) {
            this.planning = planning;
            this.workRoutes = Map.copyOf(workRoutes);
            this.policyRoute = policyRoute;
            this.guardRoutes = Map.copyOf(guardRoutes);
            LinkedHashSet<PublishedOwner> allOwners = new LinkedHashSet<>();
            allOwners.add(planning.published);
            workRoutes.values().forEach(route -> allOwners.add(route.published));
            if (policyRoute != null) {
                allOwners.add(policyRoute.published);
            }
            guardRoutes.values().forEach(route -> allOwners.add(route.published));
            this.publishedOwners = List.copyOf(allOwners);
            allOwners.remove(planning.published);
            this.additionalOwners = List.copyOf(allOwners);
        }

        private FakeScheduleCapabilityAccess access() {
            return FakeScheduleCapabilityAccess.this;
        }

        @Override
        public String sourceType() {
            return planning.sourceType;
        }

        @Override
        public Set<ScheduleCapabilityOwner> owners() {
            return publishedOwners.stream()
                    .map(published -> published.owner)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        @Override
        public Optional<ScheduledSourceDescriptor> descriptor() {
            synchronized (lock) {
                ensureActive();
                return Optional.of(planning.descriptor);
            }
        }

        @Override
        public Optional<ScheduledSourceExecutor> sourceExecutor() {
            synchronized (lock) {
                ensureActive();
                return Optional.of(planning.executor);
            }
        }

        @Override
        public Optional<ScheduledWorkExecutor> workExecutor(String workType) {
            synchronized (lock) {
                ensureActive();
                WorkRoute route = workRoutes.get(workType);
                return route == null ? Optional.empty() : Optional.of(route.executor);
            }
        }

        @Override
        public Map<String, ScheduledWorkExecutor> workExecutors() {
            synchronized (lock) {
                ensureActive();
                Map<String, ScheduledWorkExecutor> values = new LinkedHashMap<>();
                workRoutes.forEach((id, route) -> values.put(id, route.executor));
                return Map.copyOf(values);
            }
        }

        @Override
        public Optional<ScheduleCapabilityOwner> workExecutorOwner(String workType) {
            synchronized (lock) {
                ensureActive();
                WorkRoute route = workRoutes.get(workType);
                return route == null ? Optional.empty() : Optional.of(route.published.owner);
            }
        }

        @Override
        public Map<String, ScheduleCapabilityOwner> workExecutorOwners() {
            synchronized (lock) {
                ensureActive();
                Map<String, ScheduleCapabilityOwner> values = new LinkedHashMap<>();
                workRoutes.forEach((id, route) -> values.put(id, route.published.owner));
                return Map.copyOf(values);
            }
        }

        @Override
        public Optional<ScheduledCredentialPolicy> credentialPolicy() {
            synchronized (lock) {
                ensureActive();
                return policyRoute == null ? Optional.empty() : Optional.of(policyRoute.policy);
            }
        }

        @Override
        public Optional<ScheduleCapabilityOwner> credentialPolicyOwner() {
            synchronized (lock) {
                ensureActive();
                return policyRoute == null
                        ? Optional.empty()
                        : Optional.of(policyRoute.published.owner);
            }
        }

        @Override
        public Optional<ScheduledExecutionGuard> guard(String guardId) {
            synchronized (lock) {
                ensureActive();
                GuardRoute route = guardRoutes.get(guardId);
                return route == null ? Optional.empty() : Optional.of(route.guard);
            }
        }

        @Override
        public Map<String, ScheduledExecutionGuard> guards() {
            synchronized (lock) {
                ensureActive();
                Map<String, ScheduledExecutionGuard> values = new LinkedHashMap<>();
                guardRoutes.forEach((id, route) -> values.put(id, route.guard));
                return Map.copyOf(values);
            }
        }

        @Override
        public Optional<ScheduleCapabilityOwner> guardOwner(String guardId) {
            synchronized (lock) {
                ensureActive();
                GuardRoute route = guardRoutes.get(guardId);
                return route == null ? Optional.empty() : Optional.of(route.published.owner);
            }
        }

        @Override
        public Map<String, ScheduleCapabilityOwner> guardOwners() {
            synchronized (lock) {
                ensureActive();
                Map<String, ScheduleCapabilityOwner> values = new LinkedHashMap<>();
                guardRoutes.forEach((id, route) -> values.put(id, route.published.owner));
                return Map.copyOf(values);
            }
        }

        @Override
        public ScheduledCancellation cancellation() {
            synchronized (lock) {
                ensureActive();
                return () -> publishedOwners.stream()
                        .anyMatch(published -> published.cancellationRequested);
            }
        }

        @Override
        public boolean isActive() {
            synchronized (lock) {
                return phase == LeasePhase.ACTIVE;
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (phase == LeasePhase.ACTIVE) {
                    for (PublishedOwner published : additionalOwners) {
                        release(published);
                    }
                    release(planning.published);
                }
                phase = LeasePhase.CLOSED;
                if (planning.phase == LeasePhase.TRANSFERRED) {
                    planning.phase = LeasePhase.CLOSED;
                }
            }
        }

        private void ensureActive() {
            if (phase != LeasePhase.ACTIVE) {
                throw new IllegalStateException("schedule fake execution lease is not active");
            }
        }
    }

    private static <T> Map<String, T> index(
            List<? extends T> values,
            java.util.function.Function<T, String> idReader,
            String label) {
        Map<String, T> indexed = new LinkedHashMap<>();
        for (T value : values) {
            Objects.requireNonNull(value, label);
            putUnique(indexed, requireId(idReader.apply(value), label + " id"), value, label);
        }
        return Map.copyOf(indexed);
    }

    private static Set<String> normalized(Set<String> values, String label) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (!normalized.add(requireId(value, label))) {
                throw new IllegalArgumentException("duplicate " + label + ": " + value);
            }
        }
        return Set.copyOf(normalized);
    }

    private static String requireId(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }

    private static <T> void putUnique(Map<String, T> values, String id, T value, String label) {
        if (values.putIfAbsent(id, value) != null) {
            throw new IllegalArgumentException("duplicate " + label + ": " + id);
        }
    }

    private static void rejectClashes(Map<String, ?> active, Set<String> candidates, String label) {
        for (String candidate : candidates) {
            if (active.containsKey(candidate)) {
                throw new IllegalStateException("duplicate schedule " + label + ": " + candidate);
            }
        }
    }
}
