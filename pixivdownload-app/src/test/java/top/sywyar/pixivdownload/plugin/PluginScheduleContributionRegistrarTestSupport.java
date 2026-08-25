package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.migration.LegacySchedulePersistenceDescriptor;
import top.sywyar.pixivdownload.core.schedule.migration.LegacySchedulePersistenceDescriptorProvider;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledCredentialPolicyTarget;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationAdapter;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationResult;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationRoute;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationService;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.core.schedule.capability.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.ScheduleContributionLifecycleAuthority;
import top.sywyar.pixivdownload.plugin.lifecycle.ScheduleContributionLifecycleAuthorityTestAccess;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 统一 schedule owner bundle 的准备、发布与精确撤回回归。 */
@DisplayName("外置计划能力注册器共享夹具")
abstract class PluginScheduleContributionRegistrarTestSupport {

    protected static final ScheduleContributionLifecycleAuthority AUTHORITY =
            ScheduleContributionLifecycleAuthorityTestAccess.create();
    protected static final Map<PluginScheduleContributionRegistrar, PluginRegistry> ACTIVE_REGISTRIES =
            new ConcurrentHashMap<>();

    protected static AnnotationConfigApplicationContext completeChildContext(String sourceType, String workType) {
        return completeChildContext(List.of(sourceDescriptor(sourceType, workType)));
    }

    protected static AnnotationConfigApplicationContext completeChildContext(
            List<ScheduledSourceDescriptor> descriptors) {
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        registerCompleteBeans(child, descriptors);
        child.refresh();
        return child;
    }

    protected static AnnotationConfigApplicationContext legacyMigrationChild(
            String sourceType,
            String workType,
            LegacyScheduledTaskMigrationAdapter adapter) {
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        ScheduledSourceDescriptor descriptor = sourceDescriptor(sourceType, workType, sourceType.toUpperCase());
        registerCompleteBeans(child, List.of(descriptor));
        child.registerBean("legacy-migration-adapter", LegacyScheduledTaskMigrationAdapter.class, () -> adapter);
        child.registerBean("legacy-persistence", LegacySchedulePersistenceDescriptorProvider.class,
                () -> () -> List.of(new LegacySchedulePersistenceDescriptor(
                        sourceType, sourceType + ".definition", 1,
                        Set.of(workType), descriptor.credentialPolicyIds())));
        child.refresh();
        return child;
    }

    protected static PluginScheduleContributionRegistrar registrar(ScheduleCapabilityRegistry registry) {
        LegacyScheduledTaskMigrationService noOp = (reservation, adapter) ->
                new LegacyScheduledTaskMigrationService.OwnerMigrationReport(
                        registry.reservedMigrationSnapshot(reservation).ownerPluginId(), 0, 0, 0, 0);
        return registrar(registry, noOp);
    }

    protected static PluginScheduleContributionRegistrar registrar(
            ScheduleCapabilityRegistry registry,
            LegacyScheduledTaskMigrationService migrationService) {
        PluginRegistry activeRegistry = new PluginRegistry(List.of());
        PluginScheduleContributionRegistrar registrar =
                ScheduleCapabilityRegistryTestAccess.registrar(
                        registry, migrationService, activeRegistry);
        ACTIVE_REGISTRIES.put(registrar, activeRegistry);
        return registrar;
    }

    protected static Optional<ScheduleCapabilityPublication> register(
            PluginScheduleContributionRegistrar registrar,
            PluginRegistry.RegisteredPlugin registered,
            ConfigurableApplicationContext childContext) {
        PluginRegistry activeRegistry = ACTIVE_REGISTRIES.get(registrar);
        if (activeRegistry == null) {
            throw new IllegalStateException("missing active plugin registry for registrar test");
        }
        if (activeRegistry.registeredPlugins().stream()
                .anyMatch(current -> current.id().equals(registered.id()))) {
            activeRegistry.unregister(registered.id());
        }
        activeRegistry.register(registered);
        return registrar.register(AUTHORITY, registered, childContext);
    }

    protected static Optional<ScheduleCapabilityPublication> registerModern(
            PluginScheduleContributionRegistrar registrar,
            PluginRegistry.RegisteredPlugin registered) {
        try (AnnotationConfigApplicationContext child = completeChildContext(
                registered.plugin().scheduledSourceDescriptors())) {
            return register(registrar, registered, child);
        }
    }

    protected static void registerCompleteBeans(
            AnnotationConfigApplicationContext child, String sourceType, String workType) {
        registerCompleteBeans(child, List.of(sourceDescriptor(sourceType, workType)));
    }

    protected static void registerCompleteBeans(
            AnnotationConfigApplicationContext child,
            List<ScheduledSourceDescriptor> descriptors) {
        LinkedHashSet<String> workTypes = new LinkedHashSet<>();
        LinkedHashSet<String> policyIds = new LinkedHashSet<>();
        LinkedHashSet<String> guardIds = new LinkedHashSet<>();
        for (int index = 0; index < descriptors.size(); index++) {
            ScheduledSourceDescriptor descriptor = descriptors.get(index);
            String beanSuffix = Integer.toString(index);
            child.registerBean("source-executor-" + beanSuffix, ScheduledSourceExecutor.class,
                    () -> sourceExecutor(descriptor.sourceType()));
            workTypes.addAll(descriptor.possibleWorkTypes());
            policyIds.addAll(descriptor.credentialPolicyIds());
            guardIds.addAll(descriptor.guardIds());
        }
        for (String workType : workTypes) {
            child.registerBean("work-executor-" + workType, ScheduledWorkExecutor.class,
                    () -> workExecutor(workType));
        }
        for (String policyId : policyIds) {
            child.registerBean("credential-policy-" + policyId, ScheduledCredentialPolicy.class,
                    () -> credentialPolicy(policyId));
        }
        for (String guardId : guardIds) {
            child.registerBean("execution-guard-" + guardId, ScheduledExecutionGuard.class,
                    () -> executionGuard(guardId));
        }
    }

    protected static PluginRegistry.RegisteredPlugin registeredFeature(
            String id, String packageId, long generation,
            List<ScheduledSourceDescriptor> descriptors) {
        return new PluginRegistry.RegisteredPlugin(
                feature(id, descriptors), PluginSource.EXTERNAL,
                PluginScheduleContributionRegistrarTestSupport.class.getClassLoader(), packageId, generation);
    }

    protected static PixivFeaturePlugin feature(
            String id,
            List<ScheduledSourceDescriptor> descriptors) {
        return new PixivFeaturePlugin() {
            @Override public String id() { return id; }
            @Override public String displayName() { return id + ".label"; }
            @Override public String description() { return id + ".summary"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<StaticResourceContribution> staticResources() {
                return List.of(new StaticResourceContribution(
                        "classpath:/test/", "/test/"));
            }
            @Override public List<ScheduledSourceDescriptor> scheduledSourceDescriptors() { return descriptors; }
        };
    }

    protected static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timeout");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted");
        }
    }

    protected static void throwPending(AtomicReference<Error> pending) {
        Error failure = pending.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
    }

    protected static ScheduledSourceDescriptor sourceDescriptor(
            String sourceType, String workType, String... legacyAliases) {
        return sourceDescriptor(
                sourceType,
                workType,
                Set.of(sourceType + "-policy"),
                Set.of(sourceType + "-guard"),
                legacyAliases);
    }

    protected static ScheduledSourceDescriptor sourceDescriptor(
            String sourceType,
            String workType,
            Set<String> credentialPolicyIds,
            Set<String> guardIds,
            String... legacyAliases) {
        return new ScheduledSourceDescriptor(
                sourceType, Set.of(legacyAliases), sourceType + ".definition", 1,
                new ScheduledSourcePresentation("test", "source.name", "source.description", "schedule", "neutral"),
                Set.of("schedule"), Set.of(workType), credentialPolicyIds, guardIds,
                null);
    }

    protected static ScheduleOwnerBundle ownerBundle(
            ScheduleCapabilityOwner owner,
            List<ScheduledSourceDescriptor> descriptors) {
        return ownerBundle(owner, descriptors, List.of());
    }

    protected static ScheduleOwnerBundle ownerBundle(
            ScheduleCapabilityOwner owner,
            List<ScheduledSourceDescriptor> descriptors,
            List<ScheduledCredentialPolicy> additionalPolicies) {
        LinkedHashSet<String> workTypes = new LinkedHashSet<>();
        LinkedHashSet<String> policyIds = new LinkedHashSet<>();
        LinkedHashSet<String> guardIds = new LinkedHashSet<>();
        descriptors.forEach(descriptor -> {
            workTypes.addAll(descriptor.possibleWorkTypes());
            policyIds.addAll(descriptor.credentialPolicyIds());
            guardIds.addAll(descriptor.guardIds());
        });
        List<ScheduledCredentialPolicy> policies = new ArrayList<>();
        policyIds.stream().map(PluginScheduleContributionRegistrarTestSupport::credentialPolicy)
                .forEach(policies::add);
        policies.addAll(additionalPolicies);
        return ScheduleOwnerBundle.prepare(
                owner,
                descriptors,
                descriptors.stream().map(descriptor -> sourceExecutor(descriptor.sourceType())).toList(),
                workTypes.stream().map(PluginScheduleContributionRegistrarTestSupport::workExecutor).toList(),
                policies,
                guardIds.stream().map(PluginScheduleContributionRegistrarTestSupport::executionGuard).toList());
    }

    protected static ScheduledSourceExecutor sourceExecutor(String sourceType) {
        ScheduledSourceExecutor executor = mock(ScheduledSourceExecutor.class);
        when(executor.sourceType()).thenReturn(sourceType);
        return executor;
    }

    protected static ScheduledWorkExecutor workExecutor(String workType) {
        ScheduledWorkExecutor executor = mock(ScheduledWorkExecutor.class);
        when(executor.workType()).thenReturn(workType);
        return executor;
    }

    protected static ScheduledCredentialPolicy credentialPolicy(String policyId) {
        ScheduledCredentialPolicy policy = mock(ScheduledCredentialPolicy.class);
        when(policy.policyId()).thenReturn(policyId);
        return policy;
    }

    protected static ScheduledExecutionGuard executionGuard(String guardId) {
        ScheduledExecutionGuard guard = mock(ScheduledExecutionGuard.class);
        when(guard.guardId()).thenReturn(guardId);
        return guard;
    }
}
