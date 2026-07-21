package top.sywyar.pixivdownload.core.schedule.capability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationRoute;
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
            ScheduleLeaseState leaseState,
            ScheduleGenerationDrain drain
    ) {
    }

    private record ReservedOwner(
            ScheduleCapabilityReservation token,
            ScheduleOwnerBundle bundle,
            Map<String, String> credentialPolicyOwnersById
    ) {
    }

    private enum RetirementPhase {
        RETIRED,
        ACKNOWLEDGED
    }

    /** Host-only proof for an exact withdrawn publication; never retains an owner bundle or plugin Bean. */
    private record RetiredOwner(
            ScheduleCapabilityPublication publication,
            ScheduleLeaseState leaseState,
            ScheduleGenerationDrain drain,
            RetirementPhase phase
    ) {
        private static RetiredOwner from(PublishedOwner owner) {
            return new RetiredOwner(
                    owner.publication(), owner.leaseState(), owner.drain(), RetirementPhase.RETIRED);
        }

        private boolean matches(ScheduleCapabilityPublication expected) {
            return publication == expected;
        }

        private boolean matches(ScheduleGenerationDrain expected) {
            return drain == expected
                    && publication.owner().equals(expected.owner())
                    && publication.publicationId() == expected.publicationId();
        }

        private RetiredOwner acknowledged() {
            return phase == RetirementPhase.ACKNOWLEDGED
                    ? this
                    : new RetiredOwner(publication, leaseState, drain, RetirementPhase.ACKNOWLEDGED);
        }
    }

    private record SourceRoute(
            ScheduleCapabilityOwner owner,
            long publicationId,
            String sourceType,
            ScheduledSourceDescriptor descriptor,
            ScheduledSourceExecutor executor
    ) {
    }

    private record WorkRoute(
            ScheduleCapabilityOwner owner,
            long publicationId,
            String workType,
            ScheduledWorkExecutor executor
    ) {
    }

    private record CapabilityEntry<T>(
            ScheduleCapabilityOwner owner,
            long publicationId,
            String capabilityId,
            T capability
    ) {
    }

    private record Snapshot(
            long revision,
            Map<ScheduleCapabilityOwner, PublishedOwner> owners,
            Map<String, SourceRoute> sourcesByName,
            Map<String, SourceRoute> sourcesByCanonical,
            Map<String, WorkRoute> worksByType,
            Map<String, CapabilityEntry<ScheduledCredentialPolicy>> credentialPolicies,
            Map<String, CapabilityEntry<ScheduledExecutionGuard>> guards,
            SnapshotView view
    ) {
        static Snapshot empty(String epoch) {
            SnapshotView view = new SnapshotView(epoch, 0L, List.of());
            return new Snapshot(0L, Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Map.of(), view);
        }
    }

    private final Object lock = new Object();
    private final String epoch = UUID.randomUUID().toString();
    private volatile Snapshot snapshot = Snapshot.empty(epoch);
    private final Map<Long, ReservedOwner> reservations = new LinkedHashMap<>();
    private final Map<Long, RetiredOwner> retirementProofs = new LinkedHashMap<>();
    private final Predicate<String> ownerAdmission;
    private final Runnable postReserveProbe;
    private final Runnable postCommitProbe;
    private final Runnable postWithdrawProbe;
    private final Runnable postRetirementAcknowledgeProbe;
    private final Runnable postRetirementForgetProbe;
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
        this(ownerAdmission, () -> {
        }, postCommitProbe, () -> {
        }, () -> {
        }, () -> {
        }, postLeaseAcquireProbe, beforeLeaseAcquirePublishProbe,
                beforeLeaseReleaseProbe, afterLeaseReleaseProbe);
    }

    ScheduleCapabilityRegistry(
            Predicate<String> ownerAdmission,
            Runnable postReserveProbe,
            Runnable postCommitProbe,
            Runnable postWithdrawProbe,
            Runnable postRetirementAcknowledgeProbe,
            Runnable postRetirementForgetProbe,
            Runnable postLeaseAcquireProbe,
            Runnable beforeLeaseAcquirePublishProbe,
            Runnable beforeLeaseReleaseProbe,
            Runnable afterLeaseReleaseProbe) {
        this.ownerAdmission = Objects.requireNonNull(ownerAdmission, "schedule owner admission");
        this.postReserveProbe = Objects.requireNonNull(postReserveProbe, "post-reserve probe");
        this.postCommitProbe = Objects.requireNonNull(postCommitProbe, "post-commit probe");
        this.postWithdrawProbe = Objects.requireNonNull(postWithdrawProbe, "post-withdraw probe");
        this.postRetirementAcknowledgeProbe = Objects.requireNonNull(
                postRetirementAcknowledgeProbe, "post-retirement-acknowledge probe");
        this.postRetirementForgetProbe = Objects.requireNonNull(
                postRetirementForgetProbe, "post-retirement-forget probe");
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
     * 在不改变可见 snapshot 的前提下完整校验并保留一个 owner 的全部命名 claim。
     * 迁移可在返回后于 registry 锁外执行；任何结束路径都必须 commit 或 release 同一个 token。
     */
    ScheduleCapabilityReservation allocateReservation(ScheduleCapabilityOwner owner) {
        Objects.requireNonNull(owner, "schedule capability owner");
        synchronized (lock) {
            long reservationId = Math.incrementExact(nextReservationId);
            ScheduleCapabilityReservation reservation = new ScheduleCapabilityReservation(owner, reservationId);
            nextReservationId = reservationId;
            return reservation;
        }
    }

    void reserve(ScheduleCapabilityReservation reservation, ScheduleOwnerBundle bundle) {
        Objects.requireNonNull(reservation, "schedule capability reservation");
        Objects.requireNonNull(bundle, "bundle");
        if (bundle.isEmpty()) {
            throw new IllegalStateException("empty schedule capability bundle: " + bundle.owner());
        }
        if (!reservation.owner().equals(bundle.owner())) {
            throw new IllegalStateException("schedule reservation owner does not match bundle: "
                    + reservation.owner());
        }
        synchronized (lock) {
            Map<ScheduleCapabilityOwner, PublishedOwner> validationOwners = validationOwners();
            rejectActiveOwnerClash(validationOwners, bundle.owner());
            validationOwners.put(bundle.owner(), new PublishedOwner(
                    bundle, -reservation.reservationId(), reservationToken(reservation.reservationId()),
                    null, new ScheduleLeaseState(), null));
            rebuild(epoch, snapshot.revision(), validationOwners);
            Map<String, String> credentialPolicyOwnersById =
                    migrationCredentialPolicyOwners(bundle);
            reservations.put(reservation.reservationId(), new ReservedOwner(
                    reservation, bundle, credentialPolicyOwnersById));
            postReserveProbe.run();
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
            reservation.bindCommit(publication, activationToken, leaseState);
            ScheduleCapabilityReservation.CommitBinding binding = reservation.commitBinding();
            Map<ScheduleCapabilityOwner, PublishedOwner> nextOwners = new LinkedHashMap<>(snapshot.owners());
            nextOwners.put(bundle.owner(), new PublishedOwner(
                    bundle, publicationId, binding.activationToken(), publication,
                    leaseState, binding.drain()));
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
                    reservationToken(reserved.token().reservationId()), null,
                    new ScheduleLeaseState(), null));
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
            if (current == null) {
                RetiredOwner proof = retirementProofs.get(publication.publicationId());
                return proof != null && proof.matches(publication)
                        ? Optional.of(proof.drain()) : Optional.empty();
            }
            if (current.publication() != publication) {
                return Optional.empty();
            }
            Optional<ScheduleGenerationDrain> result = Optional.of(current.drain());
            Map<ScheduleCapabilityOwner, PublishedOwner> nextOwners = new LinkedHashMap<>(snapshot.owners());
            nextOwners.remove(publication.owner());
            Snapshot next = rebuild(epoch, snapshot.revision() + 1L, nextOwners);
            current.leaseState().retire();
            retirementProofs.put(publication.publicationId(), RetiredOwner.from(current));
            snapshot = next;
            postWithdrawProbe.run();
            return result;
        }
    }

    /**
     * Roll back either side of a reservation commit. This also covers a fatal thrown after the visible snapshot
     * changed but before the publication token reached the registrar.
     */
    ScheduleGenerationDrain rollback(ScheduleCapabilityReservation reservation) {
        if (reservation == null) {
            return null;
        }
        synchronized (lock) {
            ScheduleCapabilityReservation.CommitBinding binding = reservation.commitBinding();
            if (binding != null) {
                PublishedOwner current = snapshot.owners().get(reservation.owner());
                if (current != null && current.publication() == binding.publication()) {
                    Map<ScheduleCapabilityOwner, PublishedOwner> nextOwners =
                            new LinkedHashMap<>(snapshot.owners());
                    nextOwners.remove(reservation.owner());
                    Snapshot next = rebuild(epoch, snapshot.revision() + 1L, nextOwners);
                    current.leaseState().retire();
                    retirementProofs.put(
                            binding.publication().publicationId(), RetiredOwner.from(current));
                    snapshot = next;
                    reservations.remove(reservation.reservationId());
                    postWithdrawProbe.run();
                    return current.drain();
                }
                RetiredOwner proof = retirementProofs.get(binding.publication().publicationId());
                if (proof != null && proof.matches(binding.publication())) {
                    reservations.remove(reservation.reservationId());
                    return proof.drain();
                }
            }
            ReservedOwner reserved = reservations.get(reservation.reservationId());
            if (reserved != null && reserved.token() == reservation) {
                reservations.remove(reservation.reservationId());
            }
            return null;
        }
    }

    boolean acknowledgeRetired(ScheduleGenerationDrain drain) {
        Objects.requireNonNull(drain, "schedule generation drain");
        synchronized (lock) {
            RetiredOwner proof = retirementProofs.get(drain.publicationId());
            if (proof == null || !proof.matches(drain)) {
                throw new IllegalStateException("schedule generation drain is not retired: "
                        + drain.owner() + "#" + drain.publicationId());
            }
            if (proof.phase() == RetirementPhase.ACKNOWLEDGED) {
                return true;
            }
            retirementProofs.put(drain.publicationId(), proof.acknowledged());
            postRetirementAcknowledgeProbe.run();
            return true;
        }
    }

    boolean forgetRetirementAcknowledgement(ScheduleGenerationDrain drain) {
        Objects.requireNonNull(drain, "schedule generation drain");
        synchronized (lock) {
            RetiredOwner proof = retirementProofs.get(drain.publicationId());
            if (proof == null) {
                return false;
            }
            if (!proof.matches(drain) || proof.phase() != RetirementPhase.ACKNOWLEDGED) {
                throw new IllegalStateException("schedule generation drain acknowledgement mismatch: "
                        + drain.owner() + "#" + drain.publicationId());
            }
            retirementProofs.remove(drain.publicationId());
            postRetirementForgetProbe.run();
            return true;
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
        SourceRoute route = find(snapshot.sourcesByName(), sourceTypeOrAlias);
        return route == null ? Optional.empty() : Optional.of(handle(
                ScheduleCapabilityHandle.Kind.SOURCE_DESCRIPTOR, route.sourceType(), route.owner(),
                route.publicationId()));
    }

    public Optional<ScheduleCapabilityHandle<ScheduledSourceExecutor>> resolveSourceExecutor(
            String sourceTypeOrAlias) {
        SourceRoute route = find(snapshot.sourcesByName(), sourceTypeOrAlias);
        return route == null ? Optional.empty() : Optional.of(handle(
                ScheduleCapabilityHandle.Kind.SOURCE_EXECUTOR, route.sourceType(), route.owner(),
                route.publicationId()));
    }

    public Optional<ScheduleCapabilityHandle<ScheduledWorkExecutor>> resolveWorkExecutor(String workType) {
        WorkRoute route = find(snapshot.worksByType(), workType);
        if (route == null) {
            return Optional.empty();
        }
        return Optional.of(handle(ScheduleCapabilityHandle.Kind.WORK_EXECUTOR,
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
            SourceRoute current = snapshot.sourcesByName().get(sourceTypeOrAlias);
            if (current == null) {
                return Optional.empty();
            }
            ScheduleCapabilityOwner owner = current.owner();
            long publicationId = current.publicationId();
            PublishedOwner published = currentPublishedOwner(snapshot, owner, publicationId);
            if (published == null) {
                return Optional.empty();
            }
            ScheduleLeaseRoot root = new ScheduleLeaseRoot();
            ScheduleLeaseState.LeaseToken leaseToken = ScheduleLeaseState.LeaseToken.root(root);
            return Optional.of(new SchedulePlanningLease(
                    owner,
                    publicationId,
                    published.activationToken(),
                    current.sourceType(),
                    published.leaseState(),
                    root,
                    leaseToken,
                    current.descriptor(),
                    current.executor()));
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
            SourceRoute current = snapshot.sourcesByCanonical().get(lease.sourceType());
            if (published == null || published.leaseState() != lease.leaseState()
                    || !published.activationToken().equals(lease.activationToken())
                    || !matchesSourceOwner(current, lease)) {
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
                SourceRoute source = current.sourcesByCanonical().get(planning.sourceType());
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
        return prepareExpansion(planning, workTypes, policyId, guardIds);
    }

    private Optional<ScheduleExecutionLease> prepareExpansion(
            SchedulePlanningLease planning,
            Set<String> workTypes,
            String credentialPolicyId,
            Set<String> guardIds) {
        if (planning == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            return planning.whileActive(() -> prepareActiveExpansion(
                    planning, workTypes, credentialPolicyId, guardIds));
        }
    }

    private Optional<ScheduleExecutionLease> prepareActiveExpansion(
            SchedulePlanningLease planning,
            Set<String> workTypes,
            String credentialPolicyId,
            Set<String> guardIds) {
        Snapshot current = snapshot;
        PublishedOwner sourceOwner = currentPublishedOwner(
                current, planning.owner(), planning.publicationId());
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
            throw new IllegalArgumentException("execution plan requests undeclared credential policy for source: "
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
        for (String workType : workTypes) {
            WorkRoute route = current.worksByType().get(workType);
            if (route == null) {
                return Optional.empty();
            }
            PublishedOwner published = currentPublishedOwner(current, route.owner(), route.publicationId());
            if (published == null) {
                return Optional.empty();
            }
            requiredOwners.put(route.owner(), published);
            workExecutors.put(workType, route.executor());
            workExecutorOwners.put(workType, route.owner());
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
                workExecutors, workExecutorOwners,
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
            SourceRoute current,
            SchedulePlanningLease lease) {
        return current != null
                && current.owner().equals(lease.owner())
                && current.publicationId() == lease.publicationId();
    }

    private static Snapshot rebuild(
            String epoch,
            long revision,
            Map<ScheduleCapabilityOwner, PublishedOwner> mutableOwners) {
        Map<ScheduleCapabilityOwner, PublishedOwner> owners = Map.copyOf(mutableOwners);
        Map<String, SourceRoute> sourcesByName = new LinkedHashMap<>();
        Map<String, SourceRoute> sourcesByCanonical = new LinkedHashMap<>();
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
                SourceRoute route = new SourceRoute(owner, published.publicationId(),
                        descriptor.sourceType(), descriptor.descriptor(), executor.executor());
                putUnique(sourcesByCanonical, descriptor.sourceType(), route,
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

            ownerViews.add(new OwnerView(
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
                Map.copyOf(sourcesByName),
                Map.copyOf(sourcesByCanonical),
                Map.copyOf(works),
                Map.copyOf(policies),
                Map.copyOf(guards),
                view);
    }

    private static String reservationToken(long reservationId) {
        return "reservation-" + reservationId;
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
                SourceRoute route = current.sourcesByCanonical().get(handle.capabilityId());
                yield matches(route, handle) ? route.descriptor() : null;
            }
            case SOURCE_EXECUTOR -> {
                SourceRoute route = current.sourcesByCanonical().get(handle.capabilityId());
                yield matches(route, handle) ? route.executor() : null;
            }
            case WORK_EXECUTOR -> {
                WorkRoute route = current.worksByType().get(handle.capabilityId());
                yield matches(route, handle) ? route.executor() : null;
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
