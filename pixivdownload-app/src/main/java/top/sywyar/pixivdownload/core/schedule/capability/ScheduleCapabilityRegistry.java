package top.sywyar.pixivdownload.core.schedule.capability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationRoute;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Predicate;

/**
 * 计划任务能力的 owner 原子注册中心。每个 owner 的来源、作品、凭证与 Guard 只通过一个不可变快照发布；
 * resolve 只返回纯值句柄，调用插件行为前必须取得代际租约。
 */
@Component
public class ScheduleCapabilityRegistry {

    /** 已验证 reservation 对应的迁移纯值快照；它本身不授予迁移权限。 */
    public record ReservedMigrationSnapshot(
            String ownerPluginId,
            Map<String, LegacyScheduledTaskMigrationRoute> routes
    ) {
        public ReservedMigrationSnapshot {
            if (ownerPluginId == null || ownerPluginId.isBlank()) {
                throw new IllegalArgumentException("migration owner plugin id must not be blank");
            }
            routes = Map.copyOf(routes);
        }
    }

    /** 不含插件 Bean 的 owner 观测视图。 */
    public record OwnerView(
            ScheduleCapabilityOwner owner,
            long publicationId,
            String activationToken,
            Set<String> legacySourceTypes,
            Set<String> sourceTypes,
            Set<String> sourceAliases,
            Set<String> workTypes,
            Set<String> credentialPolicyIds,
            Set<String> guardIds,
            List<ScheduledSourceDescriptor> sourceDescriptors
    ) {
        public OwnerView {
            if (activationToken == null || activationToken.isBlank()) {
                throw new IllegalArgumentException("schedule activation token must not be blank");
            }
            legacySourceTypes = Set.copyOf(legacySourceTypes);
            sourceTypes = Set.copyOf(sourceTypes);
            sourceAliases = Set.copyOf(sourceAliases);
            workTypes = Set.copyOf(workTypes);
            credentialPolicyIds = Set.copyOf(credentialPolicyIds);
            guardIds = Set.copyOf(guardIds);
            sourceDescriptors = List.copyOf(sourceDescriptors);
        }
    }

    /** 单次 volatile 发布的纯数据观测视图。 */
    public record SnapshotView(String epoch, long revision, List<OwnerView> owners) {
        public SnapshotView {
            if (epoch == null || epoch.isBlank()) {
                throw new IllegalArgumentException("schedule capability epoch must not be blank");
            }
            owners = List.copyOf(owners);
        }
    }

    private record PublishedOwner(
            ScheduleOwnerBundle bundle,
            long publicationId,
            String activationToken,
            ScheduleCapabilityPublication publication,
            ScheduleLeaseState leaseState
    ) {
    }

    private record ReservedOwner(
            ScheduleCapabilityReservation token,
            ScheduleOwnerBundle bundle,
            Map<String, String> credentialPolicyOwnersById
    ) {
    }

    private record NewSourceRoute(
            ScheduleCapabilityOwner owner,
            long publicationId,
            String sourceType,
            ScheduledSourceDescriptor descriptor,
            ScheduledSourceExecutor executor
    ) {
    }

    private record LegacySourceRoute(
            ScheduleCapabilityOwner owner,
            long publicationId,
            String sourceType,
            ScheduledSourceProvider provider
    ) {
    }

    private record WorkRoute(
            ScheduleCapabilityOwner owner,
            long publicationId,
            String workType,
            ScheduledWorkExecutor executor,
            ScheduledWorkRunner legacyRunner
    ) {
        WorkRoute withExecutor(ScheduledWorkExecutor value) {
            return new WorkRoute(owner, publicationId, workType, value, legacyRunner);
        }

        WorkRoute withLegacyRunner(ScheduledWorkRunner value) {
            return new WorkRoute(owner, publicationId, workType, executor, value);
        }
    }

    private record CapabilityEntry<T>(
            ScheduleCapabilityOwner owner,
            long publicationId,
            String capabilityId,
            T capability
    ) {
    }

    private record SourceClaim(
            ScheduleCapabilityOwner owner,
            String newCanonical,
            String legacyCanonical
    ) {
        SourceClaim withNewCanonical(String value) {
            return new SourceClaim(owner, value, legacyCanonical);
        }

        SourceClaim withLegacyCanonical(String value) {
            return new SourceClaim(owner, newCanonical, value);
        }
    }

    private record Snapshot(
            long revision,
            Map<ScheduleCapabilityOwner, PublishedOwner> owners,
            Map<String, NewSourceRoute> newSourcesByName,
            Map<String, NewSourceRoute> newSourcesByCanonical,
            Map<String, LegacySourceRoute> legacySourcesByName,
            Map<String, LegacySourceRoute> legacySourcesByCanonical,
            Map<String, WorkRoute> worksByType,
            Map<String, CapabilityEntry<ScheduledCredentialPolicy>> credentialPolicies,
            Map<String, CapabilityEntry<ScheduledExecutionGuard>> guards,
            SnapshotView view
    ) {
        static Snapshot empty(String epoch) {
            SnapshotView view = new SnapshotView(epoch, 0L, List.of());
            return new Snapshot(0L, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Map.of(), Map.of(), view);
        }
    }

    private final Object lock = new Object();
    private final String epoch = UUID.randomUUID().toString();
    private volatile Snapshot snapshot = Snapshot.empty(epoch);
    private final Map<Long, ReservedOwner> reservations = new LinkedHashMap<>();
    private final Predicate<String> ownerAdmission;
    private final Runnable postCommitProbe;
    private final Runnable postLeaseAcquireProbe;
    private final Runnable beforeLeaseAcquirePublishProbe;
    private final Runnable beforeLeaseReleaseProbe;
    private final Runnable afterLeaseReleaseProbe;
    private long nextPublicationId;
    private long nextReservationId;

    /** Standalone registry constructor used by focused tests and host-only migration fixtures. */
    public ScheduleCapabilityRegistry() {
        this(ignored -> true, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        });
    }

    /** Production admission follows the same lifecycle STARTED barrier as plugin HTTP serving. */
    @Autowired
    public ScheduleCapabilityRegistry(PluginLifecycleState lifecycleState) {
        this(lifecycleState::acceptsNewRequests, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        });
    }

    ScheduleCapabilityRegistry(Predicate<String> ownerAdmission, Runnable postCommitProbe) {
        this(ownerAdmission, postCommitProbe, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        });
    }

    ScheduleCapabilityRegistry(
            Predicate<String> ownerAdmission,
            Runnable postCommitProbe,
            Runnable postLeaseAcquireProbe) {
        this(ownerAdmission, postCommitProbe, postLeaseAcquireProbe, () -> {
        }, () -> {
        }, () -> {
        });
    }

    ScheduleCapabilityRegistry(
            Predicate<String> ownerAdmission,
            Runnable postCommitProbe,
            Runnable postLeaseAcquireProbe,
            Runnable beforeLeaseAcquirePublishProbe,
            Runnable beforeLeaseReleaseProbe,
            Runnable afterLeaseReleaseProbe) {
        this.ownerAdmission = Objects.requireNonNull(ownerAdmission, "schedule owner admission");
        this.postCommitProbe = Objects.requireNonNull(postCommitProbe, "post-commit probe");
        this.postLeaseAcquireProbe = Objects.requireNonNull(
                postLeaseAcquireProbe, "post-lease-acquire probe");
        this.beforeLeaseAcquirePublishProbe = Objects.requireNonNull(
                beforeLeaseAcquirePublishProbe, "before schedule lease acquire publish probe");
        this.beforeLeaseReleaseProbe = Objects.requireNonNull(
                beforeLeaseReleaseProbe, "before schedule lease release probe");
        this.afterLeaseReleaseProbe = Objects.requireNonNull(
                afterLeaseReleaseProbe, "after schedule lease release probe");
    }

    public SnapshotView snapshotView() {
        return snapshot.view();
    }

    /**
     * 一次发布一个 owner 的完整 bundle。冲突或校验失败时，既有 snapshot 引用与 revision 均保持不变。
     */
    ScheduleCapabilityPublication publish(ScheduleOwnerBundle bundle) {
        ScheduleCapabilityReservation reservation = reserve(bundle);
        boolean committed = false;
        try {
            ScheduleCapabilityPublication publication = commit(reservation);
            committed = true;
            return publication;
        } finally {
            if (!committed) {
                release(reservation);
            }
        }
    }

    /**
     * 在不改变可见 snapshot 的前提下完整校验并保留一个 owner 的全部命名 claim。
     * 迁移可在返回后于 registry 锁外执行；任何结束路径都必须 commit 或 release 同一个 token。
     */
    ScheduleCapabilityReservation reserve(ScheduleOwnerBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        if (bundle.isEmpty()) {
            throw new IllegalStateException("empty schedule capability bundle: " + bundle.owner());
        }
        synchronized (lock) {
            Map<ScheduleCapabilityOwner, PublishedOwner> validationOwners = validationOwners();
            rejectActiveOwnerClash(validationOwners, bundle.owner());
            long reservationId = Math.incrementExact(nextReservationId);
            validationOwners.put(bundle.owner(), new PublishedOwner(
                    bundle, -reservationId, reservationToken(reservationId), null,
                    new ScheduleLeaseState()));
            rebuild(epoch, snapshot.revision(), validationOwners);
            Map<String, String> credentialPolicyOwnersById =
                    migrationCredentialPolicyOwners(bundle);
            ScheduleCapabilityReservation token =
                    new ScheduleCapabilityReservation(bundle.owner(), reservationId);
            reservations.put(reservationId, new ReservedOwner(
                    token, bundle, credentialPolicyOwnersById));
            nextReservationId = reservationId;
            return token;
        }
    }

    /**
     * 校验 token 确为本 registry 当前仍有效的对象实例，并从 registry 内部预留项推导 owner 与 route。
     * 调用方不能提交 owner 或 route 覆盖该快照。
     */
    public ReservedMigrationSnapshot reservedMigrationSnapshot(
            ScheduleCapabilityReservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        synchronized (lock) {
            ReservedOwner reserved = reservations.get(reservation.reservationId());
            if (reserved == null || reserved.token() != reservation) {
                throw new IllegalStateException("unknown schedule capability reservation: " + reservation);
            }
            return new ReservedMigrationSnapshot(
                    reserved.bundle().owner().featurePluginId(),
                    reserved.bundle().legacyMigrationRoutes(
                            reserved.credentialPolicyOwnersById()));
        }
    }

    /** 把已预留且尚未对读者可见的 bundle 原子发布；无效、已释放或已提交 token 一律拒绝。 */
    ScheduleCapabilityPublication commit(ScheduleCapabilityReservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        synchronized (lock) {
            ReservedOwner reserved = reservations.get(reservation.reservationId());
            if (reserved == null || reserved.token() != reservation) {
                throw new IllegalStateException("unknown schedule capability reservation: " + reservation);
            }
            ScheduleOwnerBundle bundle = reserved.bundle();
            rejectActiveOwnerClash(snapshot.owners(), bundle.owner());
            long publicationId = nextPublicationId + 1L;
            ScheduleCapabilityPublication publication =
                    new ScheduleCapabilityPublication(bundle.owner(), publicationId);
            ScheduleLeaseState leaseState = new ScheduleLeaseState(
                    beforeLeaseAcquirePublishProbe,
                    beforeLeaseReleaseProbe,
                    afterLeaseReleaseProbe);
            String activationToken = UUID.randomUUID().toString();
            Map<ScheduleCapabilityOwner, PublishedOwner> nextOwners = new LinkedHashMap<>(snapshot.owners());
            nextOwners.put(bundle.owner(), new PublishedOwner(
                    bundle, publicationId, activationToken, publication, leaseState));
            Snapshot next = rebuild(epoch, snapshot.revision() + 1L, nextOwners);
            nextPublicationId = publicationId;
            snapshot = next;
            postCommitProbe.run();
            reservations.remove(reservation.reservationId());
            return publication;
        }
    }

    /** 释放尚未提交的命名 claim；返回 false 表示 token 已提交、已释放或不属于本 registry。 */
    boolean release(ScheduleCapabilityReservation reservation) {
        if (reservation == null) {
            return false;
        }
        synchronized (lock) {
            ReservedOwner reserved = reservations.get(reservation.reservationId());
            if (reserved == null || reserved.token() != reservation) {
                return false;
            }
            reservations.remove(reservation.reservationId());
            return true;
        }
    }

    private Map<ScheduleCapabilityOwner, PublishedOwner> validationOwners() {
        Map<ScheduleCapabilityOwner, PublishedOwner> owners = new LinkedHashMap<>(snapshot.owners());
        for (ReservedOwner reserved : reservations.values()) {
            owners.put(reserved.token().owner(), new PublishedOwner(
                    reserved.bundle(), -reserved.token().reservationId(),
                    reservationToken(reserved.token().reservationId()), null, new ScheduleLeaseState()));
        }
        return owners;
    }

    /**
     * 迁移只能信任已经发布的 policy，或当前正预留 bundle 自己携带的 policy。其它尚未提交的
     * reservation 可能随后释放，不能据此移动旧 secret。
     */
    private Map<String, String> migrationCredentialPolicyOwners(ScheduleOwnerBundle bundle) {
        Map<String, String> ownersById = new LinkedHashMap<>();
        snapshot.credentialPolicies().forEach((policyId, entry) ->
                ownersById.put(policyId, entry.owner().featurePluginId()));
        bundle.credentialPolicies().forEach(policy ->
                ownersById.put(policy.policyId(), bundle.owner().featurePluginId()));
        return Map.copyOf(ownersById);
    }

    /**
     * 精确撤回一次 publication。旧 generation 或同 generation 的旧 publication token 都不能撤回当前能力。
     */
    Optional<ScheduleGenerationDrain> withdraw(ScheduleCapabilityPublication publication) {
        if (publication == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            PublishedOwner current = snapshot.owners().get(publication.owner());
            if (current == null || current.publication() != publication) {
                return Optional.empty();
            }
            Map<ScheduleCapabilityOwner, PublishedOwner> nextOwners = new LinkedHashMap<>(snapshot.owners());
            nextOwners.remove(publication.owner());
            Snapshot next = rebuild(epoch, snapshot.revision() + 1L, nextOwners);
            current.leaseState().retire();
            snapshot = next;
            return Optional.of(new ScheduleGenerationDrain(
                    publication.owner(), publication.publicationId(), current.leaseState()));
        }
    }

    Optional<ScheduleCapabilityPublication> publication(ScheduleCapabilityOwner owner) {
        if (owner == null) {
            return Optional.empty();
        }
        PublishedOwner published = snapshot.owners().get(owner);
        if (published == null) {
            return Optional.empty();
        }
        return Optional.of(published.publication());
    }

    /** 解析当前活动 feature owner 的纯值句柄，用于保护不直接对应某一 source/work 的插件内运行任务。 */
    public Optional<ScheduleCapabilityHandle<ScheduleCapabilityOwner>> resolveOwner(String featurePluginId) {
        if (featurePluginId == null || featurePluginId.isBlank()) {
            return Optional.empty();
        }
        Snapshot current = snapshot;
        for (Map.Entry<ScheduleCapabilityOwner, PublishedOwner> entry : current.owners().entrySet()) {
            if (entry.getKey().featurePluginId().equals(featurePluginId)) {
                return Optional.of(handle(ScheduleCapabilityHandle.Kind.OWNER,
                        featurePluginId, entry.getKey(), entry.getValue().publicationId()));
            }
        }
        return Optional.empty();
    }

    public Optional<ScheduleCapabilityHandle<ScheduledSourceDescriptor>> resolveSourceDescriptor(
            String sourceTypeOrAlias) {
        NewSourceRoute route = find(snapshot.newSourcesByName(), sourceTypeOrAlias);
        return route == null ? Optional.empty() : Optional.of(handle(
                ScheduleCapabilityHandle.Kind.SOURCE_DESCRIPTOR, route.sourceType(), route.owner(),
                route.publicationId()));
    }

    public Optional<ScheduleCapabilityHandle<ScheduledSourceExecutor>> resolveSourceExecutor(
            String sourceTypeOrAlias) {
        NewSourceRoute route = find(snapshot.newSourcesByName(), sourceTypeOrAlias);
        return route == null ? Optional.empty() : Optional.of(handle(
                ScheduleCapabilityHandle.Kind.SOURCE_EXECUTOR, route.sourceType(), route.owner(),
                route.publicationId()));
    }

    public Optional<ScheduleCapabilityHandle<ScheduledSourceProvider>> resolveLegacySource(
            String sourceTypeOrAlias) {
        LegacySourceRoute route = find(snapshot.legacySourcesByName(), sourceTypeOrAlias);
        return route == null ? Optional.empty() : Optional.of(handle(
                ScheduleCapabilityHandle.Kind.LEGACY_SOURCE, route.sourceType(), route.owner(),
                route.publicationId()));
    }

    public Optional<ScheduleCapabilityHandle<ScheduledWorkExecutor>> resolveWorkExecutor(String workType) {
        WorkRoute route = find(snapshot.worksByType(), workType);
        if (route == null || route.executor() == null) {
            return Optional.empty();
        }
        return Optional.of(handle(ScheduleCapabilityHandle.Kind.WORK_EXECUTOR,
                route.workType(), route.owner(), route.publicationId()));
    }

    public Optional<ScheduleCapabilityHandle<ScheduledWorkRunner>> resolveLegacyWorkRunner(String workType) {
        WorkRoute route = find(snapshot.worksByType(), workType);
        if (route == null || route.legacyRunner() == null) {
            return Optional.empty();
        }
        return Optional.of(handle(ScheduleCapabilityHandle.Kind.LEGACY_WORK_RUNNER,
                route.workType(), route.owner(), route.publicationId()));
    }

    public Optional<ScheduleCapabilityHandle<ScheduledCredentialPolicy>> resolveCredentialPolicy(String policyId) {
        CapabilityEntry<ScheduledCredentialPolicy> entry = find(snapshot.credentialPolicies(), policyId);
        return entry == null ? Optional.empty() : Optional.of(handle(
                ScheduleCapabilityHandle.Kind.CREDENTIAL_POLICY, entry.capabilityId(), entry.owner(),
                entry.publicationId()));
    }

    public Optional<ScheduleCapabilityHandle<ScheduledExecutionGuard>> resolveGuard(String guardId) {
        CapabilityEntry<ScheduledExecutionGuard> entry = find(snapshot.guards(), guardId);
        return entry == null ? Optional.empty() : Optional.of(handle(
                ScheduleCapabilityHandle.Kind.EXECUTION_GUARD, entry.capabilityId(), entry.owner(),
                entry.publicationId()));
    }

    /**
     * 以纯句柄准备一个尚未激活的行为能力租约；调用方必须先建立 try/finally，再调用 {@link #activate}。
     */
    public <T> Optional<ScheduleSingleCapabilityLease<T>> prepareAcquire(ScheduleCapabilityHandle<T> handle) {
        if (handle == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            PublishedOwner published = currentPublishedOwner(snapshot, handle.owner(), handle.publicationId());
            if (published == null) {
                return Optional.empty();
            }
            T capability = resolveCapability(snapshot, handle);
            if (capability == null) {
                return Optional.empty();
            }
            ScheduleLeaseRoot root = new ScheduleLeaseRoot();
            ScheduleLeaseState.LeaseToken leaseToken = ScheduleLeaseState.LeaseToken.root(root);
            return Optional.of(new ScheduleSingleCapabilityLease<>(
                    handle, published.leaseState(), root, leaseToken, capability));
        }
    }

    /** 在调用方已持有 lease 的 try/finally 内激活单能力租约。 */
    public boolean activate(ScheduleSingleCapabilityLease<?> lease) {
        if (lease == null) {
            return false;
        }
        synchronized (lock) {
            ScheduleCapabilityHandle<?> handle = lease.handle();
            PublishedOwner published = currentPublishedOwner(
                    snapshot, handle.owner(), handle.publicationId());
            if (published == null || published.leaseState() != lease.leaseState()
                    || resolveCapability(snapshot, handle) == null) {
                return false;
            }
            if (!published.leaseState().tryAcquire(lease.leaseToken())) {
                return false;
            }
            if (!lease.root().activateSingle()) {
                published.leaseState().release(lease.leaseToken());
                return false;
            }
            postLeaseAcquireProbe.run();
            return true;
        }
    }

    /**
     * 按 canonical 或 legacy alias 准备来源 planning lease；调用方必须先建立 try/finally，再调用 {@link #activate}。
     */
    public Optional<SchedulePlanningLease> prepareSource(String sourceTypeOrAlias) {
        if (sourceTypeOrAlias == null || sourceTypeOrAlias.isBlank()) {
            return Optional.empty();
        }
        synchronized (lock) {
            NewSourceRoute currentNew = snapshot.newSourcesByName().get(sourceTypeOrAlias);
            LegacySourceRoute currentLegacy = snapshot.legacySourcesByName().get(sourceTypeOrAlias);
            if (currentNew == null && currentLegacy == null) {
                return Optional.empty();
            }
            ScheduleCapabilityOwner owner = currentNew != null ? currentNew.owner() : currentLegacy.owner();
            long publicationId = currentNew != null
                    ? currentNew.publicationId() : currentLegacy.publicationId();
            if (currentNew != null && currentLegacy != null
                    && (!owner.equals(currentLegacy.owner())
                    || publicationId != currentLegacy.publicationId())) {
                throw new IllegalStateException("inconsistent schedule source owner snapshot: " + sourceTypeOrAlias);
            }
            PublishedOwner published = currentPublishedOwner(snapshot, owner, publicationId);
            if (published == null) {
                return Optional.empty();
            }
            String canonical = currentNew != null ? currentNew.sourceType() : currentLegacy.sourceType();
            ScheduleLeaseRoot root = new ScheduleLeaseRoot();
            ScheduleLeaseState.LeaseToken leaseToken = ScheduleLeaseState.LeaseToken.root(root);
            return Optional.of(new SchedulePlanningLease(
                    owner,
                    publicationId,
                    published.activationToken(),
                    canonical,
                    published.leaseState(),
                    root,
                    leaseToken,
                    currentNew == null ? null : currentNew.descriptor(),
                    currentNew == null ? null : currentNew.executor(),
                    currentLegacy == null ? null : currentLegacy.provider()));
        }
    }

    /** 在调用方已持有 lease 的 try/finally 内激活来源 planning 租约。 */
    public boolean activate(SchedulePlanningLease lease) {
        if (lease == null) {
            return false;
        }
        synchronized (lock) {
            PublishedOwner published = currentPublishedOwner(
                    snapshot, lease.owner(), lease.publicationId());
            NewSourceRoute currentNew = snapshot.newSourcesByCanonical().get(lease.sourceType());
            LegacySourceRoute currentLegacy = snapshot.legacySourcesByCanonical().get(lease.sourceType());
            if (published == null || published.leaseState() != lease.leaseState()
                    || !published.activationToken().equals(lease.activationToken())
                    || !matchesSourceOwner(currentNew, currentLegacy, lease)) {
                return false;
            }
            if (!published.leaseState().tryAcquire(lease.leaseToken())) {
                return false;
            }
            if (!lease.root().activatePlanning()) {
                published.leaseState().release(lease.leaseToken());
                return false;
            }
            postLeaseAcquireProbe.run();
            return true;
        }
    }

    /**
     * 仅当来源 planning 仍属于当前 publication 时执行一次短宿主持久化操作。
     *
     * <p>current 复核、操作执行与 publication 撤回共用 registry 锁，因此撤回要么先完成并使操作不执行，
     * 要么等待操作（包括其事务提交）返回。调用方必须在进入本方法前完成全部插件回调，操作内不得调用插件行为。
     */
    public <T> Optional<T> whileCurrentPublication(
            SchedulePlanningLease planning,
            Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (planning == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            return planning.whileActive(() -> {
                Snapshot current = snapshot;
                PublishedOwner published = currentPublishedOwner(
                        current, planning.owner(), planning.publicationId());
                NewSourceRoute source = current.newSourcesByCanonical().get(planning.sourceType());
                if (published == null
                        || published.leaseState() != planning.leaseState()
                        || !published.activationToken().equals(planning.activationToken())
                        || source == null
                        || !source.owner().equals(planning.owner())
                        || source.publicationId() != planning.publicationId()) {
                    return Optional.empty();
                }
                return Optional.of(Objects.requireNonNull(
                        operation.get(), "current publication operation result"));
            });
        }
    }

    /**
     * 按新 execution plan 一次取得全部 work/policy/Guard owner；任一缺失或 owner 正在撤回时不取得部分租约。
     */
    public Optional<ScheduleExecutionLease> prepareExpansion(
            SchedulePlanningLease planning, ScheduledExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Set<String> workTypes = normalizedIds(plan.requiredWorkTypes(), "required work type");
        Set<String> guardIds = new LinkedHashSet<>();
        for (ScheduledGuardBinding binding : plan.guards()) {
            if (binding == null) {
                throw new IllegalArgumentException("execution plan contains null guard binding");
            }
            String guardId = normalizedId(binding.guardId(), "guard id");
            if (!guardIds.add(guardId)) {
                throw new IllegalArgumentException("duplicate guard binding: " + guardId);
            }
        }
        String policyId = plan.credentialPolicyId();
        if (policyId != null) {
            policyId = normalizedId(policyId, "credential policy id");
        }
        return prepareExpansion(planning, workTypes, policyId, guardIds, true);
    }

    /** 为现有 Pixiv/小说执行壳一次取得全部旧 work runner owner。 */
    public Optional<ScheduleExecutionLease> prepareLegacyExpansion(
            SchedulePlanningLease planning, Set<String> requiredWorkTypes) {
        Set<String> workTypes = normalizedIds(requiredWorkTypes, "legacy work type");
        return prepareExpansion(planning, workTypes, null, Set.of(), false);
    }

    private Optional<ScheduleExecutionLease> prepareExpansion(
            SchedulePlanningLease planning,
            Set<String> workTypes,
            String credentialPolicyId,
            Set<String> guardIds,
            boolean useNewCapabilities) {
        if (planning == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            return planning.whileActive(() -> prepareActiveExpansion(
                    planning, workTypes, credentialPolicyId, guardIds, useNewCapabilities));
        }
    }

    private Optional<ScheduleExecutionLease> prepareActiveExpansion(
            SchedulePlanningLease planning,
            Set<String> workTypes,
            String credentialPolicyId,
            Set<String> guardIds,
            boolean useNewCapabilities) {
        Snapshot current = snapshot;
        PublishedOwner sourceOwner = currentPublishedOwner(
                current, planning.owner(), planning.publicationId());
        if (sourceOwner == null
                || sourceOwner.leaseState() != planning.leaseState()
                || !sourceOwner.leaseState().isAccepting()) {
            return Optional.empty();
        }

        ScheduledSourceDescriptor descriptor = planning.descriptor().orElse(null);
        if (useNewCapabilities) {
            if (descriptor == null || planning.sourceExecutor().isEmpty()) {
                return Optional.empty();
            }
            if (!descriptor.possibleWorkTypes().containsAll(workTypes)) {
                throw new IllegalArgumentException("execution plan requests undeclared work type for source: "
                        + planning.sourceType());
            }
            if (credentialPolicyId != null
                    && !descriptor.credentialPolicyIds().contains(credentialPolicyId)) {
                throw new IllegalArgumentException("execution plan requests undeclared credential policy for source: "
                        + planning.sourceType());
            }
            if (!descriptor.guardIds().containsAll(guardIds)) {
                throw new IllegalArgumentException("execution plan requests undeclared guard for source: "
                        + planning.sourceType());
            }
        } else if (planning.legacySourceProvider().isEmpty()) {
            return Optional.empty();
        }

        Map<ScheduleCapabilityOwner, PublishedOwner> requiredOwners = new LinkedHashMap<>();
        requiredOwners.put(planning.owner(), sourceOwner);
        Map<String, ScheduledWorkExecutor> workExecutors = new LinkedHashMap<>();
        Map<String, ScheduleCapabilityOwner> workExecutorOwners = new LinkedHashMap<>();
        Map<String, ScheduledWorkRunner> legacyWorkRunners = new LinkedHashMap<>();
        for (String workType : workTypes) {
            WorkRoute route = current.worksByType().get(workType);
            if (route == null || (useNewCapabilities && route.executor() == null)
                    || (!useNewCapabilities && route.legacyRunner() == null)) {
                return Optional.empty();
            }
            PublishedOwner published = currentPublishedOwner(current, route.owner(), route.publicationId());
            if (published == null) {
                return Optional.empty();
            }
            requiredOwners.put(route.owner(), published);
            if (useNewCapabilities) {
                workExecutors.put(workType, route.executor());
                workExecutorOwners.put(workType, route.owner());
            } else {
                legacyWorkRunners.put(workType, route.legacyRunner());
            }
        }

        ScheduledCredentialPolicy credentialPolicy = null;
        ScheduleCapabilityOwner credentialPolicyOwner = null;
        if (credentialPolicyId != null) {
            CapabilityEntry<ScheduledCredentialPolicy> entry =
                    current.credentialPolicies().get(credentialPolicyId);
            if (entry == null) {
                return Optional.empty();
            }
            PublishedOwner published = currentPublishedOwner(
                    current, entry.owner(), entry.publicationId());
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
            PublishedOwner published = currentPublishedOwner(
                    current, entry.owner(), entry.publicationId());
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
                planning.owner(), planning.publicationId(), sourceOwner.leaseState(), planning.leaseToken());
        List<ScheduleExecutionLease.OwnerState> additionalOwners = new ArrayList<>(requiredOwners.size() - 1);
        for (Map.Entry<ScheduleCapabilityOwner, PublishedOwner> required : requiredOwners.entrySet()) {
            if (required.getKey().equals(planning.owner())) {
                continue;
            }
            additionalOwners.add(new ScheduleExecutionLease.OwnerState(
                    required.getKey(), required.getValue().publicationId(), required.getValue().leaseState(),
                    ScheduleLeaseState.LeaseToken.branch(planning.root(), branch)));
        }

        return Optional.of(new ScheduleExecutionLease(
                planning, branch, sourceState, additionalOwners, source,
                workExecutors, workExecutorOwners, legacyWorkRunners,
                credentialPolicy, credentialPolicyOwner, guards, guardOwners));
    }

    /** 在调用方已持有 execution lease 的 try/finally 内原子激活全部附加 owner 并转移根状态。 */
    public boolean activate(ScheduleExecutionLease execution) {
        if (execution == null) {
            return false;
        }
        synchronized (lock) {
            SchedulePlanningLease planning = execution.planning();
            if (!planning.isActive() || !matchesOwnerState(execution.sourceOwnerState())) {
                return false;
            }
            for (ScheduleExecutionLease.OwnerState ownerState : execution.additionalOwnerStates()) {
                if (!matchesOwnerState(ownerState)) {
                    return false;
                }
            }
            for (ScheduleExecutionLease.OwnerState ownerState : execution.additionalOwnerStates()) {
                if (!ownerState.state().tryAcquire(ownerState.leaseToken())) {
                    execution.branch().close();
                    return false;
                }
                postLeaseAcquireProbe.run();
            }
            if (!execution.branch().activate()) {
                return false;
            }
            if (!execution.additionalOwnerStates().isEmpty()) {
                postLeaseAcquireProbe.run();
            }
            SchedulePlanningLease.TransferredSource source = planning.prepareTransfer();
            if (!planning.root().transferPlanningToExecution()) {
                execution.branch().close();
                return false;
            }
            planning.finishTransfer(source);
            postLeaseAcquireProbe.run();
            return true;
        }
    }

    private boolean matchesOwnerState(ScheduleExecutionLease.OwnerState expected) {
        PublishedOwner current = currentPublishedOwner(
                snapshot, expected.owner(), expected.publicationId());
        return current != null && current.leaseState() == expected.state();
    }

    private static boolean matchesSourceOwner(
            NewSourceRoute currentNew,
            LegacySourceRoute currentLegacy,
            SchedulePlanningLease lease) {
        boolean newMatches = currentNew != null
                && currentNew.owner().equals(lease.owner())
                && currentNew.publicationId() == lease.publicationId();
        boolean legacyMatches = currentLegacy != null
                && currentLegacy.owner().equals(lease.owner())
                && currentLegacy.publicationId() == lease.publicationId();
        return newMatches || legacyMatches;
    }

    private static Snapshot rebuild(
            String epoch,
            long revision,
            Map<ScheduleCapabilityOwner, PublishedOwner> mutableOwners) {
        Map<ScheduleCapabilityOwner, PublishedOwner> owners = Map.copyOf(mutableOwners);
        Map<String, NewSourceRoute> newByName = new LinkedHashMap<>();
        Map<String, NewSourceRoute> newByCanonical = new LinkedHashMap<>();
        Map<String, LegacySourceRoute> legacyByName = new LinkedHashMap<>();
        Map<String, LegacySourceRoute> legacyByCanonical = new LinkedHashMap<>();
        Map<String, SourceClaim> sourceClaims = new LinkedHashMap<>();
        Map<String, WorkRoute> works = new LinkedHashMap<>();
        Map<String, CapabilityEntry<ScheduledCredentialPolicy>> policies = new LinkedHashMap<>();
        Map<String, CapabilityEntry<ScheduledExecutionGuard>> guards = new LinkedHashMap<>();
        List<OwnerView> ownerViews = new ArrayList<>();

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
                NewSourceRoute route = new NewSourceRoute(owner, published.publicationId(),
                        descriptor.sourceType(), descriptor.descriptor(), executor.executor());
                putUnique(newByCanonical, descriptor.sourceType(), route,
                        "scheduled source descriptor type");
                claimSource(sourceClaims, descriptor.sourceType(), owner, descriptor.sourceType(), true);
                putUnique(newByName, descriptor.sourceType(), route, "scheduled source name");
                for (String alias : descriptor.aliases()) {
                    aliases.add(alias);
                    claimSource(sourceClaims, alias, owner, descriptor.sourceType(), true);
                    putUnique(newByName, alias, route, "scheduled source alias");
                }
            }

            for (ScheduleOwnerBundle.LegacySourceEntry legacy : bundle.legacySources()) {
                LegacySourceRoute route = new LegacySourceRoute(owner, published.publicationId(),
                        legacy.sourceType(), legacy.provider());
                putUnique(legacyByCanonical, legacy.sourceType(), route, "legacy scheduled source type");
                claimSource(sourceClaims, legacy.sourceType(), owner, legacy.sourceType(), false);
                putUnique(legacyByName, legacy.sourceType(), route, "legacy scheduled source name");
                for (String alias : legacy.aliases()) {
                    aliases.add(alias);
                    claimSource(sourceClaims, alias, owner, legacy.sourceType(), false);
                    putUnique(legacyByName, alias, route, "legacy scheduled source alias");
                }
            }

            for (ScheduleOwnerBundle.WorkExecutorEntry work : bundle.workExecutors()) {
                WorkRoute previous = works.get(work.workType());
                if (previous == null) {
                    works.put(work.workType(), new WorkRoute(owner, published.publicationId(),
                            work.workType(), work.executor(), null));
                } else {
                    requireSameWorkOwner(previous, owner, published.publicationId(), work.workType());
                    if (previous.executor() != null) {
                        throw duplicate("scheduled work executor", work.workType(), owner, previous.owner());
                    }
                    works.put(work.workType(), previous.withExecutor(work.executor()));
                }
            }
            for (ScheduleOwnerBundle.LegacyWorkRunnerEntry work : bundle.legacyWorkRunners()) {
                WorkRoute previous = works.get(work.workType());
                if (previous == null) {
                    works.put(work.workType(), new WorkRoute(owner, published.publicationId(),
                            work.workType(), null, work.runner()));
                } else {
                    requireSameWorkOwner(previous, owner, published.publicationId(), work.workType());
                    if (previous.legacyRunner() != null) {
                        throw duplicate("legacy scheduled work runner", work.workType(), owner, previous.owner());
                    }
                    works.put(work.workType(), previous.withLegacyRunner(work.runner()));
                }
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

            Set<String> workTypes = new LinkedHashSet<>();
            bundle.workExecutors().forEach(value -> workTypes.add(value.workType()));
            bundle.legacyWorkRunners().forEach(value -> workTypes.add(value.workType()));
            ownerViews.add(new OwnerView(
                    owner,
                    published.publicationId(),
                    published.activationToken(),
                    sortedSet(bundle.legacySources().stream()
                            .map(ScheduleOwnerBundle.LegacySourceEntry::sourceType).toList()),
                    sortedSet(bundle.sourceDescriptors().stream()
                            .map(ScheduleOwnerBundle.SourceDescriptorEntry::sourceType).toList()),
                    sortedSet(aliases),
                    sortedSet(workTypes),
                    sortedSet(bundle.credentialPolicies().stream()
                            .map(ScheduleOwnerBundle.CredentialPolicyEntry::policyId).toList()),
                    sortedSet(bundle.guards().stream().map(ScheduleOwnerBundle.GuardEntry::guardId).toList()),
                    bundle.sourceDescriptors().stream()
                            .map(ScheduleOwnerBundle.SourceDescriptorEntry::descriptor)
                            .sorted(Comparator.comparing(ScheduledSourceDescriptor::sourceType))
                            .toList()));
        }

        SnapshotView view = new SnapshotView(epoch, revision, ownerViews);
        return new Snapshot(
                revision,
                owners,
                Map.copyOf(newByName),
                Map.copyOf(newByCanonical),
                Map.copyOf(legacyByName),
                Map.copyOf(legacyByCanonical),
                Map.copyOf(works),
                Map.copyOf(policies),
                Map.copyOf(guards),
                view);
    }

    private static String reservationToken(long reservationId) {
        return "reservation-" + reservationId;
    }

    private static void claimSource(
            Map<String, SourceClaim> claims,
            String name,
            ScheduleCapabilityOwner owner,
            String canonical,
            boolean newSource) {
        SourceClaim previous = claims.get(name);
        if (previous == null) {
            claims.put(name, newSource
                    ? new SourceClaim(owner, canonical, null)
                    : new SourceClaim(owner, null, canonical));
            return;
        }
        if (!previous.owner().equals(owner)) {
            throw duplicate("scheduled source name", name, owner, previous.owner());
        }
        if (newSource) {
            if (previous.newCanonical() != null && !previous.newCanonical().equals(canonical)) {
                throw duplicate("scheduled source name", name, owner, previous.owner());
            }
            claims.put(name, previous.withNewCanonical(canonical));
        } else {
            if (previous.legacyCanonical() != null && !previous.legacyCanonical().equals(canonical)) {
                throw duplicate("legacy scheduled source name", name, owner, previous.owner());
            }
            claims.put(name, previous.withLegacyCanonical(canonical));
        }
    }

    private static void requireSameWorkOwner(
            WorkRoute previous,
            ScheduleCapabilityOwner owner,
            long publicationId,
            String workType) {
        if (!previous.owner().equals(owner) || previous.publicationId() != publicationId) {
            throw duplicate("scheduled work type", workType, owner, previous.owner());
        }
    }

    private static void rejectActiveOwnerClash(
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

    private PublishedOwner currentPublishedOwner(
            Snapshot current, ScheduleCapabilityOwner owner, long publicationId) {
        PublishedOwner published = current.owners().get(owner);
        if (published == null || published.publicationId() != publicationId
                || !published.leaseState().isAccepting()
                || !ownerAdmission.test(owner.featurePluginId())) {
            return null;
        }
        return published;
    }

    @SuppressWarnings("unchecked")
    private static <T> T resolveCapability(Snapshot current, ScheduleCapabilityHandle<T> handle) {
        Object value = switch (handle.kind()) {
            case OWNER -> {
                PublishedOwner published = current.owners().get(handle.owner());
                yield published != null
                        && published.publicationId() == handle.publicationId()
                        && handle.capabilityId().equals(handle.owner().featurePluginId())
                        ? handle.owner() : null;
            }
            case SOURCE_DESCRIPTOR -> {
                NewSourceRoute route = current.newSourcesByCanonical().get(handle.capabilityId());
                yield matches(route, handle) ? route.descriptor() : null;
            }
            case SOURCE_EXECUTOR -> {
                NewSourceRoute route = current.newSourcesByCanonical().get(handle.capabilityId());
                yield matches(route, handle) ? route.executor() : null;
            }
            case LEGACY_SOURCE -> {
                LegacySourceRoute route = current.legacySourcesByCanonical().get(handle.capabilityId());
                yield matches(route, handle) ? route.provider() : null;
            }
            case WORK_EXECUTOR -> {
                WorkRoute route = current.worksByType().get(handle.capabilityId());
                yield matches(route, handle) ? route.executor() : null;
            }
            case LEGACY_WORK_RUNNER -> {
                WorkRoute route = current.worksByType().get(handle.capabilityId());
                yield matches(route, handle) ? route.legacyRunner() : null;
            }
            case CREDENTIAL_POLICY -> {
                CapabilityEntry<ScheduledCredentialPolicy> entry =
                        current.credentialPolicies().get(handle.capabilityId());
                yield matches(entry, handle) ? entry.capability() : null;
            }
            case EXECUTION_GUARD -> {
                CapabilityEntry<ScheduledExecutionGuard> entry = current.guards().get(handle.capabilityId());
                yield matches(entry, handle) ? entry.capability() : null;
            }
        };
        return (T) value;
    }

    private static boolean matches(NewSourceRoute route, ScheduleCapabilityHandle<?> handle) {
        return route != null && route.owner().equals(handle.owner())
                && route.publicationId() == handle.publicationId();
    }

    private static boolean matches(LegacySourceRoute route, ScheduleCapabilityHandle<?> handle) {
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

    private static <T> ScheduleCapabilityHandle<T> handle(
            ScheduleCapabilityHandle.Kind kind,
            String capabilityId,
            ScheduleCapabilityOwner owner,
            long publicationId) {
        return new ScheduleCapabilityHandle<>(kind, capabilityId, owner, publicationId);
    }

    private static <T> T find(Map<String, T> values, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return values.get(key);
    }

    private static <T> void putUnique(Map<String, T> values, String key, T value, String label) {
        T previous = values.putIfAbsent(key, value);
        if (previous != null) {
            throw new IllegalStateException("duplicate " + label + ": " + key);
        }
    }

    private static IllegalStateException duplicate(
            String label, String value,
            ScheduleCapabilityOwner candidate,
            ScheduleCapabilityOwner previous) {
        return new IllegalStateException("duplicate " + label + ": " + value
                + " (owner: " + candidate + "; already claimed by: " + previous + ")");
    }

    private static Set<String> normalizedIds(Set<String> values, String label) {
        if (values == null) {
            throw new IllegalArgumentException(label + " set must not be null");
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = normalizedId(value, label);
            if (!result.add(normalized)) {
                throw new IllegalArgumentException("duplicate " + label + ": " + normalized);
            }
        }
        return Set.copyOf(result);
    }

    private static String normalizedId(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        String normalized = value.trim();
        if (!normalized.equals(value)) {
            throw new IllegalArgumentException(label + " must already be normalized: " + value);
        }
        return normalized;
    }

    private static Set<String> sortedSet(java.util.Collection<String> values) {
        return Set.copyOf(values.stream().sorted().toList());
    }
}
