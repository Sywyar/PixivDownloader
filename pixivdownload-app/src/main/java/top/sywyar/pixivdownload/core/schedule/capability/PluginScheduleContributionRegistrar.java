package top.sywyar.pixivdownload.core.schedule.capability;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 外置插件计划任务能力的生命周期接入点。插件 getter 与 child context Bean 发现全部发生在 registry 锁外，
 * 完整能力经 {@link ScheduleOwnerBundle} 一次准备、一次发布。若 child context 提供旧任务迁移适配器，
 * 则以 registry 内部有效 reservation 完成迁移，返回后才发布行为 Bean；调用方不能自行提交 owner 或 route。
 * 撤回只接受精确 publication token，旧代或同代旧 publication 不能撤回当前能力。
 *
 * <p>本类与 registry 同包，使 reserve/commit/release 不必成为外置插件可调用的公共 API。不透明 reservation
 * 只存在于本方法栈，既不返回也不写入字段。</p>
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
    private final Object lock = new Object();
    private final Map<ScheduleCapabilityOwner, ScheduleCapabilityPublication> publications = new LinkedHashMap<>();

    PluginScheduleContributionRegistrar(
            ScheduleCapabilityRegistry registry,
            LegacyScheduledTaskMigrationService migrationService,
            PluginRegistry pluginRegistry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.migrationService = Objects.requireNonNull(migrationService, "migrationService");
        this.pluginRegistry = Objects.requireNonNull(pluginRegistry, "pluginRegistry");
    }

    /**
     * 收集并原子发布一个外置插件的完整计划任务能力。没有任何能力时返回空且不建立 publication。
     * getter/Bean 发现及 bundle 校验均在本对象锁和 registry 锁之外完成。
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
                readPluginValue(owner, "scheduledSources", registered.plugin()::scheduledSources);
        List<ScheduledWorkRunner> legacyWorkRunners =
                readChildBeans(owner, childContext, ScheduledWorkRunner.class);
        List<ScheduledSourceDescriptor> sourceDescriptors =
                readPluginValue(owner, "scheduledSourceDescriptors",
                        registered.plugin()::scheduledSourceDescriptors);
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

        ScheduleCapabilityReservation reservation = registry.reserve(bundle);
        boolean committed = false;
        try {
            if (!registry.reservedMigrationSnapshot(reservation).routes().isEmpty()) {
                List<LegacyScheduledTaskMigrationAdapter> adapters =
                        readChildBeans(owner, childContext, LegacyScheduledTaskMigrationAdapter.class);
                if (adapters.size() > 1) {
                    throw new IllegalStateException(
                            "multiple legacy schedule migration adapters for owner " + owner);
                }
                LegacyScheduledTaskMigrationAdapter adapter = adapters.isEmpty()
                        ? snapshot -> new Rejected(MIGRATION_ADAPTER_UNAVAILABLE, "{}")
                        : adapters.get(0);
                migrationService.migrateReservedOwner(reservation, adapter);
            }

            synchronized (lock) {
                if (publications.containsKey(owner)) {
                    throw new IllegalStateException("schedule capabilities already published for owner: " + owner);
                }
                ScheduleCapabilityPublication publication = registry.commit(reservation);
                publications.put(owner, publication);
                committed = true;
                return Optional.of(publication);
            }
        } finally {
            if (!committed) {
                registry.release(reservation);
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
            ScheduleCapabilityPublication current = publications.get(publication.owner());
            if (current != publication) {
                return Optional.empty();
            }
            Optional<ScheduleGenerationDrain> drain = registry.withdraw(publication);
            if (drain.isPresent()) {
                publications.remove(publication.owner());
            }
            return drain;
        }
    }

    /** 当前 owner 的精确 publication，只用于生命周期观测与测试，不暴露行为 Bean。 */
    Optional<ScheduleCapabilityPublication> publication(ScheduleCapabilityOwner owner) {
        synchronized (lock) {
            return Optional.ofNullable(publications.get(owner));
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
        } catch (RuntimeException failure) {
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
        } catch (RuntimeException failure) {
            throw boundaryFailure(owner, "childBeans[" + beanType.getName() + "]", failure);
        }
    }

    /** 插件异常在边界处转成无 cause 的宿主异常，避免生命周期对象或异步日志长期持有插件 classloader。 */
    private static IllegalStateException boundaryFailure(
            ScheduleCapabilityOwner owner, String field, RuntimeException failure) {
        return new IllegalStateException("failed to read schedule contribution '" + field
                + "' for owner " + owner + " (failureType=" + failure.getClass().getName() + ")");
    }
}
