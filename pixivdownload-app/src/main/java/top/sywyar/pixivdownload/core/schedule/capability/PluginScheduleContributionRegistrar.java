package top.sywyar.pixivdownload.core.schedule.capability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.schedule.migration.LegacySchedulePersistenceDescriptorProvider;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationAdapter;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationResult.Rejected;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationService;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.lifecycle.ScheduleContributionLifecycleAuthority;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebAssetValidator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 外置插件计划任务能力的生命周期接入点。插件 getter 与 child context Bean 发现全部发生在 registry 锁外，
 * 完整能力经 {@link ScheduleOwnerBundle} 一次准备、一次发布。若 child context 提供旧任务迁移适配器，
 * 则以 registry 内部有效 reservation 完成迁移，并由精确活动插件身份 reservation 把迁移副作用与最终发布串行绑定；
 * 返回后才发布行为 Bean，调用方不能自行提交 owner 或 route。
 * 撤回只接受精确 publication token，旧代或同代旧 publication 不能撤回当前能力。
 *
     * <p>本类与 registry 同包，使 reserve/commit/release 不必成为外置插件可调用的公共 API。不透明 reservation
     * 在任何 registry 副作用前写入宿主 slot，所有异常路径都能用同一身份继续回滚。</p>
 *
 * <p>本类只保存 owner 纯值与 publication 不透明身份句柄，不保存迁移报告、插件 Bean、classloader 或 child context。行为 Bean 的唯一长期持有点
 * 是统一 {@link ScheduleCapabilityRegistry} 的不可变快照；撤回后只由尚未释放的 execution lease 暂时持有。</p>
 */
@Component
public class PluginScheduleContributionRegistrar {

    public static final String MIGRATION_ADAPTER_UNAVAILABLE = "MIGRATION_ADAPTER_UNAVAILABLE";

    private final ScheduleCapabilityRegistry registry;
    private final LegacyScheduledTaskMigrationService migrationService;
    private final PluginRegistry pluginRegistry;
    private final PluginOwnedWebAssetValidator webAssetValidator;
    private final Runnable rollbackCleanupProbe;
    private final Object lock = new Object();
    private static final class PublicationSlot {
        private ScheduleCapabilityReservation reservation;
        private ScheduleCapabilityPublication publication;
        private ScheduleGenerationDrain drain;
        private boolean registryRetired;
        private boolean registryAcknowledged;
        private boolean registryForgotten;
        private boolean cleanupPending;
    }

    private final Map<ScheduleCapabilityOwner, PublicationSlot> publications = new LinkedHashMap<>();

    @Autowired
    PluginScheduleContributionRegistrar(
            ScheduleCapabilityRegistry registry,
            LegacyScheduledTaskMigrationService migrationService,
            PluginRegistry pluginRegistry,
            PluginOwnedWebAssetValidator webAssetValidator) {
        this(registry, migrationService, pluginRegistry, webAssetValidator, () -> {
        });
    }

    PluginScheduleContributionRegistrar(
            ScheduleCapabilityRegistry registry,
            LegacyScheduledTaskMigrationService migrationService,
            PluginRegistry pluginRegistry,
            PluginOwnedWebAssetValidator webAssetValidator,
            Runnable rollbackCleanupProbe) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.migrationService = Objects.requireNonNull(migrationService, "migrationService");
        this.pluginRegistry = Objects.requireNonNull(pluginRegistry, "pluginRegistry");
        this.webAssetValidator = Objects.requireNonNull(webAssetValidator, "webAssetValidator");
        this.rollbackCleanupProbe = Objects.requireNonNull(
                rollbackCleanupProbe, "schedule rollback cleanup probe");
    }

    /**
     * 收集并原子发布一个外置插件的完整计划任务能力。没有任何能力时返回空且不建立 publication。
     * getter/Bean 发现及 bundle 校验均在本对象锁和 registry 锁之外完成。迁移开始前再保留精确活动身份：
     * 身份若已替换则不会执行迁移；保留成功后，同身份注销会等待迁移和最终 publication 提交结束。
     */
    public Optional<ScheduleCapabilityPublication> register(
            ScheduleContributionLifecycleAuthority authority,
            PluginRegistry.RegisteredPlugin registered,
            @Nullable ConfigurableApplicationContext childContext) {
        requireAuthority(authority);
        requireActiveRegistration(registered);
        ScheduleCapabilityOwner owner = new ScheduleCapabilityOwner(
                registered.id(), registered.packageId(), registered.generation());

        List<ScheduledSourceProvider> legacySources =
                readPluginValue(owner, "scheduledSources",
                        () -> List.copyOf(registered.plugin().scheduledSources()));
        List<ScheduledWorkRunner> legacyWorkRunners =
                readChildBeans(owner, childContext, ScheduledWorkRunner.class);
        List<ScheduledSourceDescriptor> sourceDescriptors =
                readPluginValue(owner, "scheduledSourceDescriptors",
                        () -> List.copyOf(registered.plugin().scheduledSourceDescriptors()));
        sourceDescriptors.stream()
                .filter(descriptor -> descriptor != null && descriptor.frontend() != null)
                .forEach(descriptor -> webAssetValidator.validateOwnedJavaScript(
                        registered,
                        descriptor.frontend().moduleUrl(),
                        "scheduled source frontend " + descriptor.sourceType()));
        List<ScheduledSourceExecutor> sourceExecutors =
                readChildBeans(owner, childContext, ScheduledSourceExecutor.class);
        List<ScheduledWorkExecutor> workExecutors =
                readChildBeans(owner, childContext, ScheduledWorkExecutor.class);
        List<ScheduledCredentialPolicy> credentialPolicies =
                readChildBeans(owner, childContext, ScheduledCredentialPolicy.class);
        List<ScheduledExecutionGuard> guards =
                readChildBeans(owner, childContext, ScheduledExecutionGuard.class);
        List<LegacySchedulePersistenceDescriptorProvider> legacyPersistenceProviders =
                readChildBeans(owner, childContext, LegacySchedulePersistenceDescriptorProvider.class);

        ScheduleOwnerBundle bundle = ScheduleOwnerBundle.prepare(
                owner,
                legacySources,
                legacyWorkRunners,
                sourceDescriptors,
                sourceExecutors,
                workExecutors,
                credentialPolicies,
                guards,
                legacyPersistenceProviders);
        if (bundle.isEmpty()) {
            return Optional.empty();
        }

        PublicationSlot slot = new PublicationSlot();
        slot.reservation = registry.allocateReservation(owner);
        try {
            synchronized (lock) {
                rejectTrackedOwnerClash(owner);
                publications.put(owner, slot);
            }
            registry.reserve(slot.reservation, bundle);
            ScheduleCapabilityReservation reservation = slot.reservation;
            LegacyScheduledTaskMigrationAdapter migrationAdapter = null;
            if (!registry.reservedMigrationSnapshot(reservation).routes().isEmpty()) {
                List<LegacyScheduledTaskMigrationAdapter> adapters =
                        readChildBeans(owner, childContext, LegacyScheduledTaskMigrationAdapter.class);
                if (adapters.size() > 1) {
                    throw new IllegalStateException(
                            "multiple legacy schedule migration adapters for owner " + owner);
                }
                migrationAdapter = adapters.isEmpty()
                        ? snapshot -> new Rejected(MIGRATION_ADAPTER_UNAVAILABLE, "{}")
                        : adapters.get(0);
            }

            LegacyScheduledTaskMigrationAdapter preparedMigrationAdapter = migrationAdapter;
            ScheduleCapabilityPublication publication = pluginRegistry.withActiveIdentityReservation(
                    registered,
                    () -> migrateAndCommitReserved(
                            registered, owner, slot, preparedMigrationAdapter));
            return Optional.of(publication);
        } catch (Throwable failure) {
            rollbackReservation(owner, slot, failure);
            rethrowUnchecked(failure);
            throw new IllegalStateException("unreachable");
        }
    }

    /** 身份 reservation 仍有效时完成迁移与提交；失败 claim 也在释放身份前清理。 */
    private ScheduleCapabilityPublication migrateAndCommitReserved(
            PluginRegistry.RegisteredPlugin registered,
            ScheduleCapabilityOwner owner,
            PublicationSlot slot,
            @Nullable LegacyScheduledTaskMigrationAdapter migrationAdapter) {
        if (migrationAdapter != null) {
            migrationService.migrateReservedOwner(slot.reservation, migrationAdapter);
        }
        return pluginRegistry.commitIfActiveIdentity(
                registered,
                () -> {
                    synchronized (lock) {
                        if (publications.get(owner) != slot) {
                            throw new IllegalStateException(
                                    "schedule capability reservation slot changed for owner: " + owner);
                        }
                        ScheduleCapabilityPublication publication = registry.commit(slot.reservation);
                        slot.publication = publication;
                        return publication;
                    }
                });
    }

    private void rollbackReservation(
            ScheduleCapabilityOwner owner,
            PublicationSlot slot,
            Throwable primaryFailure) {
        Throwable cleanupFailure;
        synchronized (lock) {
            slot.cleanupPending = true;
            cleanupFailure = cleanupRegistrationSlot(slot);
            if (cleanupFailure == null) {
                publications.remove(owner, slot);
                slot.cleanupPending = false;
            }
        }
        if (cleanupFailure != null && cleanupFailure != primaryFailure) {
            addSuppressedSafely(primaryFailure, cleanupFailure);
        }
        if (!isFatal(primaryFailure) && isFatal(cleanupFailure)) {
            addSuppressedSafely(cleanupFailure, primaryFailure);
            rethrowFatal(cleanupFailure);
        }
    }

    /** Retry one exact failed reserve/commit cleanup; a live successful publication is never accepted here. */
    public boolean retryFailedRegistrationCleanup(
            ScheduleContributionLifecycleAuthority authority,
            ScheduleCapabilityOwner owner) {
        requireAuthority(authority);
        Objects.requireNonNull(owner, "schedule capability owner");
        synchronized (lock) {
            PublicationSlot slot = publications.get(owner);
            if (slot == null) {
                return false;
            }
            if (!slot.cleanupPending) {
                throw new IllegalStateException(
                        "schedule capability publication is active, not cleanup-pending: " + owner);
            }
            Throwable cleanupFailure = cleanupRegistrationSlot(slot);
            if (cleanupFailure != null) {
                rethrowFatal(cleanupFailure);
                throw new IllegalStateException(
                        "failed to retry schedule registration cleanup for owner " + owner
                                + " (failureType=" + cleanupFailure.getClass().getName() + ")");
            }
            publications.remove(owner, slot);
            slot.cleanupPending = false;
            return true;
        }
    }

    private Throwable cleanupRegistrationSlot(PublicationSlot slot) {
        boolean interrupted = false;
        try {
            if (!slot.registryRetired) {
                rollbackCleanupProbe.run();
                ScheduleGenerationDrain drain = registry.rollback(slot.reservation);
                if (drain != null) {
                    slot.drain = drain;
                }
                slot.registryRetired = true;
            }
            ScheduleGenerationDrain drain = slot.drain;
            if (drain != null) {
                while (!drain.isDrained()) {
                    if (!drain.awaitDrained()) {
                        interrupted = true;
                        Thread.interrupted();
                    }
                }
                if (!slot.registryAcknowledged) {
                    registry.acknowledgeRetired(drain);
                    slot.registryAcknowledged = true;
                }
                if (!slot.registryForgotten) {
                    boolean forgotten = registry.forgetRetirementAcknowledgement(drain);
                    if (!forgotten && !slot.registryAcknowledged) {
                        throw new IllegalStateException(
                                "schedule retirement acknowledgement is missing: "
                                        + drain.owner() + "#" + drain.publicationId());
                    }
                    slot.registryForgotten = true;
                }
            }
            return null;
        } catch (Throwable failure) {
            return failure;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 精确撤回一次 publication，并返回用于等待本 publication 在途 lease 归零的 drain。 */
    public Optional<ScheduleGenerationDrain> withdraw(
            ScheduleContributionLifecycleAuthority authority,
            @Nullable ScheduleCapabilityPublication publication) {
        requireAuthority(authority);
        if (publication == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            PublicationSlot current = publications.get(publication.owner());
            if (current == null || current.publication != publication) {
                return Optional.empty();
            }
            if (current.drain != null) {
                current.registryRetired = true;
                return Optional.of(current.drain);
            }
            Optional<ScheduleGenerationDrain> drain = registry.withdraw(publication);
            if (drain.isPresent()) {
                current.drain = drain.orElseThrow();
                current.registryRetired = true;
            }
            return drain;
        }
    }

    /** Recover an exact active publication after registrar return but before lifecycle field assignment. */
    public Optional<ScheduleCapabilityPublication> recoverPublication(
            ScheduleContributionLifecycleAuthority authority,
            ScheduleCapabilityOwner owner) {
        requireAuthority(authority);
        Objects.requireNonNull(owner, "schedule capability owner");
        synchronized (lock) {
            PublicationSlot slot = publications.get(owner);
            if (slot == null || slot.cleanupPending) {
                return Optional.empty();
            }
            if (slot.publication != null) {
                return Optional.of(slot.publication);
            }
            ScheduleCapabilityReservation.CommitBinding binding = slot.reservation.commitBinding();
            if (binding == null) {
                return Optional.empty();
            }
            Optional<ScheduleCapabilityPublication> active = registry.publication(owner);
            if (active.isPresent() && active.orElseThrow() == binding.publication()) {
                slot.publication = binding.publication();
                return Optional.of(slot.publication);
            }
            return Optional.empty();
        }
    }

    /** Acknowledge and forget the host-only registry proof while retaining the exact registrar slot. */
    public void acknowledgeRetired(
            ScheduleContributionLifecycleAuthority authority,
            ScheduleGenerationDrain drain) {
        requireAuthority(authority);
        Objects.requireNonNull(drain, "schedule generation drain");
        synchronized (lock) {
            PublicationSlot slot = requireDrainSlot(drain);
            if (!slot.registryRetired) {
                throw new IllegalStateException("schedule generation is not retired: "
                        + drain.owner() + "#" + drain.publicationId());
            }
            if (!slot.registryAcknowledged) {
                registry.acknowledgeRetired(drain);
                slot.registryAcknowledged = true;
            }
            if (!slot.registryForgotten) {
                boolean forgotten = registry.forgetRetirementAcknowledgement(drain);
                if (!forgotten && !slot.registryAcknowledged) {
                    throw new IllegalStateException("schedule retirement acknowledgement is missing: "
                            + drain.owner() + "#" + drain.publicationId());
                }
                slot.registryForgotten = true;
            }
        }
    }

    /** Release the final exact registrar proof only after lifecycle has closed the child context. */
    public boolean releaseRetirementProof(
            ScheduleContributionLifecycleAuthority authority,
            ScheduleGenerationDrain drain) {
        requireAuthority(authority);
        if (drain == null) {
            return false;
        }
        synchronized (lock) {
            PublicationSlot slot = publications.get(drain.owner());
            if (slot == null) {
                return false;
            }
            if (slot.drain != drain || !slot.registryForgotten) {
                throw new IllegalStateException("schedule retirement proof is not releasable: "
                        + drain.owner() + "#" + drain.publicationId());
            }
            publications.remove(drain.owner(), slot);
            return true;
        }
    }

    /** 当前 owner 的精确 publication，只用于生命周期观测与测试，不暴露行为 Bean。 */
    Optional<ScheduleCapabilityPublication> publication(ScheduleCapabilityOwner owner) {
        synchronized (lock) {
            PublicationSlot slot = publications.get(owner);
            return Optional.ofNullable(slot == null ? null : slot.publication);
        }
    }

    private PublicationSlot requireDrainSlot(ScheduleGenerationDrain drain) {
        PublicationSlot slot = publications.get(drain.owner());
        if (slot == null || slot.drain != drain || slot.publication == null
                || slot.publication.publicationId() != drain.publicationId()) {
            throw new IllegalStateException("unknown schedule generation drain: "
                    + drain.owner() + "#" + drain.publicationId());
        }
        return slot;
    }

    private void rejectTrackedOwnerClash(ScheduleCapabilityOwner candidate) {
        for (ScheduleCapabilityOwner tracked : publications.keySet()) {
            if (tracked.featurePluginId().equals(candidate.featurePluginId())) {
                throw new IllegalStateException(
                        "schedule feature owner cleanup is still tracked: " + tracked);
            }
            if (tracked.packageId().equals(candidate.packageId())) {
                throw new IllegalStateException(
                        "schedule package owner cleanup is still tracked: " + tracked);
            }
        }
    }

    private static void requireAuthority(ScheduleContributionLifecycleAuthority authority) {
        Objects.requireNonNull(authority, "plugin lifecycle authority");
    }

    private void requireActiveRegistration(PluginRegistry.RegisteredPlugin registered) {
        Objects.requireNonNull(registered, "registered");
        boolean activeIdentity = pluginRegistry.registeredPlugins().stream()
                .anyMatch(current -> current == registered);
        if (!activeIdentity) {
            throw new IllegalStateException(
                    "schedule contribution registration is not the current active plugin identity");
        }
    }

    private static <T> T readPluginValue(
            ScheduleCapabilityOwner owner, String field, Supplier<T> reader) {
        try {
            return reader.get();
        } catch (Throwable failure) {
            rethrowFatal(failure);
            throw boundaryFailure(owner, field, failure);
        }
    }

    private static <T> List<T> readChildBeans(
            ScheduleCapabilityOwner owner,
            @Nullable ConfigurableApplicationContext childContext,
            Class<T> beanType) {
        if (childContext == null) {
            return List.of();
        }
        try {
            return List.copyOf(childContext.getBeansOfType(beanType).values());
        } catch (Throwable failure) {
            rethrowFatal(failure);
            throw boundaryFailure(owner, "childBeans[" + beanType.getName() + "]", failure);
        }
    }

    /** 插件异常在边界处转成无 cause 的宿主异常，避免生命周期对象或异步日志长期持有插件 classloader。 */
    private static IllegalStateException boundaryFailure(
            ScheduleCapabilityOwner owner, String field, Throwable failure) {
        return new IllegalStateException("failed to read schedule contribution '" + field
                + "' for owner " + owner + " (failureType=" + failure.getClass().getName() + ")");
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void rethrowUnchecked(Throwable failure) {
        rethrowFatal(failure);
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("schedule contribution registration failed", failure);
    }

    private static void addSuppressedSafely(Throwable target, Throwable suppressed) {
        if (target == null || suppressed == null || target == suppressed) {
            return;
        }
        try {
            target.addSuppressed(suppressed);
        } catch (Throwable ignored) {
            // Preserve the primary lifecycle failure when diagnostic attachment itself fails.
        }
    }
}
