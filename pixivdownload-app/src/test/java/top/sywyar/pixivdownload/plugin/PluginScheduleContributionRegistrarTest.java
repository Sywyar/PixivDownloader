package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkSettings;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceFrontendContribution;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 统一 schedule owner bundle 的准备、发布与精确撤回回归。 */
@DisplayName("外置插件 schedule 能力原子注册器")
class PluginScheduleContributionRegistrarTest {

    @Test
    @DisplayName("一次发布旧来源与 child context 五类行为 Bean，并由宿主盖章 owner、package 和 generation")
    void publishesCompleteOwnerBundle() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(registry);

        try (AnnotationConfigApplicationContext child = completeChildContext("alpha", "alpha-work")) {
            ScheduleCapabilityPublication publication = registrar.register(
                    registeredFeature("ext-a", "ext-package", 7L,
                            List.of(sourceProvider("alpha", "ALPHA")),
                            List.of(sourceDescriptor("alpha", "alpha-work", "ALPHA"))), child).orElseThrow();

            assertThat(publication.owner()).isEqualTo(
                    new ScheduleCapabilityOwner("ext-a", "ext-package", 7L));
            assertThat(registry.snapshotView().owners()).singleElement().satisfies(owner -> {
                assertThat(owner.owner()).isEqualTo(publication.owner());
                assertThat(owner.legacySourceTypes()).containsExactly("alpha");
                assertThat(owner.sourceTypes()).containsExactly("alpha");
                assertThat(owner.workTypes()).containsExactly("alpha-work");
                assertThat(owner.credentialPolicyIds()).containsExactly("alpha-policy");
                assertThat(owner.guardIds()).containsExactly("alpha-guard");
            });
            assertThat(registry.resolveLegacySource("ALPHA")).isPresent();
            assertThat(registry.resolveSourceExecutor("alpha")).isPresent();
            assertThat(registry.resolveLegacyWorkRunner("alpha-work")).isPresent();
            assertThat(registry.resolveWorkExecutor("alpha-work")).isPresent();
            assertThat(registry.resolveCredentialPolicy("alpha-policy")).isPresent();
            assertThat(registry.resolveGuard("alpha-guard")).isPresent();
        }
    }

    @Test
    @DisplayName("插件 getter 或 bundle 校验失败时旧 snapshot 完整保留，不产生 publication")
    void preparationFailureDoesNotPolluteSnapshot() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(registry);
        registrar.register(registeredFeature("ext-a", "ext-a", 1L,
                List.of(sourceProvider("stable", "STABLE")), List.of()), null).orElseThrow();
        long revision = registry.snapshotView().revision();

        PixivFeaturePlugin broken = new PixivFeaturePlugin() {
            @Override public String id() { return "ext-b"; }
            @Override public String displayName() { return "ext-b.label"; }
            @Override public String description() { return "ext-b.summary"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override
            public List<ScheduledSourceDescriptor> scheduledSourceDescriptors() {
                throw new IllegalStateException("broken getter");
            }
        };
        PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                broken, PluginSource.EXTERNAL, getClass().getClassLoader(), "ext-b", 2L);

        assertThatThrownBy(() -> registrar.register(registered, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduledSourceDescriptors")
                .hasMessageContaining("IllegalStateException")
                .hasMessageNotContaining("broken getter")
                .hasNoCause();
        assertThat(registry.snapshotView().revision()).isEqualTo(revision);
        assertThat(registry.resolveLegacySource("stable")).isPresent();
        assertThat(registrar.publication(new ScheduleCapabilityOwner("ext-b", "ext-b", 2L))).isEmpty();
    }

    @Test
    @DisplayName("精确 publication 撤回会拒绝新 lease、取消旧 lease，并在旧 lease 释放后归零")
    void exactWithdrawalReturnsGenerationDrain() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(registry);
        ScheduleCapabilityPublication publication = registrar.register(
                registeredFeature("ext-a", "ext-a", 3L,
                        List.of(sourceProvider("alpha", "ALPHA")), List.of()), null).orElseThrow();
        SchedulePlanningLease lease = registry.tryAcquireSource("alpha").orElseThrow();

        ScheduleGenerationDrain drain = registrar.withdraw(publication).orElseThrow();

        assertThat(registry.tryAcquireSource("alpha")).isEmpty();
        assertThat(lease.cancellation().isCancellationRequested()).isTrue();
        assertThat(drain.activeLeaseCount()).isEqualTo(1);
        assertThat(drain.isDrained()).isFalse();
        lease.close();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("同 generation 重新发布后旧 token 不能撤回新 publication")
    void stalePublicationCannotWithdrawReplacement() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(registry);
        PluginRegistry.RegisteredPlugin registered = registeredFeature(
                "ext-a", "ext-a", 4L, List.of(sourceProvider("alpha", "ALPHA")), List.of());
        ScheduleCapabilityPublication oldPublication = registrar.register(registered, null).orElseThrow();
        registrar.withdraw(oldPublication).orElseThrow();
        ScheduleCapabilityPublication current = registrar.register(registered, null).orElseThrow();

        assertThat(current.publicationId()).isGreaterThan(oldPublication.publicationId());
        assertThat(registrar.withdraw(oldPublication)).isEmpty();
        assertThat(registry.resolveLegacySource("alpha")).isPresent();
        assertThat(registrar.withdraw(current)).isPresent();
    }

    @Test
    @DisplayName("registry 未确认撤回时保留 publication token 供一致性诊断与重试")
    void failedRegistryWithdrawalKeepsPublicationToken() {
        ScheduleCapabilityRegistry registry = mock(ScheduleCapabilityRegistry.class);
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(registry);
        ScheduleCapabilityOwner owner = new ScheduleCapabilityOwner("ext-a", "ext-a", 6L);
        ScheduleCapabilityPublication publication = mock(ScheduleCapabilityPublication.class);
        when(publication.owner()).thenReturn(owner);
        when(publication.publicationId()).thenReturn(42L);
        when(registry.publish(any(ScheduleOwnerBundle.class))).thenReturn(publication);
        when(registry.withdraw(publication)).thenReturn(Optional.empty());

        assertThat(registrar.register(registeredFeature(
                "ext-a", "ext-a", 6L, List.of(sourceProvider("alpha")), List.of()), null))
                .containsSame(publication);

        assertThat(registrar.withdraw(publication)).isEmpty();
        assertThat(registrar.publication(owner)).containsSame(publication);
    }

    @Test
    @DisplayName("child context Bean 发现不含父 context，并在 descriptor/executor 不匹配时原子拒绝")
    void childBeanDiscoveryExcludesAncestors() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(registry);
        ScheduledSourceExecutor parentExecutor = sourceExecutor("parent");

        try (AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
             AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            parent.registerBean("parent-source", ScheduledSourceExecutor.class, () -> parentExecutor);
            parent.refresh();
            child.setParent(parent);
            registerCompleteBeans(child, "alpha", "alpha-work");
            child.refresh();

            assertThat(registrar.register(registeredFeature(
                    "ext-a", "ext-a", 5L, List.of(),
                    List.of(sourceDescriptor("alpha", "alpha-work"))), child)).isPresent();
            assertThat(registry.resolveSourceExecutor("alpha")).isPresent();
            assertThat(registry.resolveSourceExecutor("parent")).isEmpty();
        }
    }

    @Test
    @DisplayName("无任何 schedule 能力的插件返回空 publication 且不改变 registry")
    void emptyPluginIsTransparent() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(registry);

        Optional<ScheduleCapabilityPublication> publication = registrar.register(
                registeredFeature("ext-a", "ext-a", 0L, List.of(), List.of()), null);

        assertThat(publication).isEmpty();
        assertThat(registry.snapshotView().owners()).isEmpty();
    }

    private static AnnotationConfigApplicationContext completeChildContext(String sourceType, String workType) {
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        registerCompleteBeans(child, sourceType, workType);
        child.refresh();
        return child;
    }

    private static void registerCompleteBeans(
            AnnotationConfigApplicationContext child, String sourceType, String workType) {
        child.registerBean("legacy-runner", ScheduledWorkRunner.class, () -> legacyRunner(workType));
        child.registerBean("source-executor", ScheduledSourceExecutor.class, () -> sourceExecutor(sourceType));
        child.registerBean("work-executor", ScheduledWorkExecutor.class, () -> workExecutor(workType));
        child.registerBean("credential-policy", ScheduledCredentialPolicy.class,
                () -> credentialPolicy(sourceType + "-policy"));
        child.registerBean("execution-guard", ScheduledExecutionGuard.class,
                () -> executionGuard(sourceType + "-guard"));
    }

    private static PluginRegistry.RegisteredPlugin registeredFeature(
            String id, String packageId, long generation,
            List<ScheduledSourceProvider> legacySources,
            List<ScheduledSourceDescriptor> descriptors) {
        return new PluginRegistry.RegisteredPlugin(
                feature(id, legacySources, descriptors), PluginSource.EXTERNAL,
                PluginScheduleContributionRegistrarTest.class.getClassLoader(), packageId, generation);
    }

    private static PixivFeaturePlugin feature(
            String id, List<ScheduledSourceProvider> legacySources,
            List<ScheduledSourceDescriptor> descriptors) {
        return new PixivFeaturePlugin() {
            @Override public String id() { return id; }
            @Override public String displayName() { return id + ".label"; }
            @Override public String description() { return id + ".summary"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<ScheduledSourceProvider> scheduledSources() { return legacySources; }
            @Override public List<ScheduledSourceDescriptor> scheduledSourceDescriptors() { return descriptors; }
        };
    }

    private static ScheduledSourceProvider sourceProvider(String type, String... aliases) {
        return new ScheduledSourceProvider() {
            @Override public String type() { return type; }
            @Override public Set<String> legacyTypeNames() { return Set.of(aliases); }
        };
    }

    private static ScheduledSourceDescriptor sourceDescriptor(
            String sourceType, String workType, String... legacyAliases) {
        return new ScheduledSourceDescriptor(
                sourceType, Set.of(legacyAliases), sourceType + ".definition", 1,
                new ScheduledSourcePresentation("test", "source.name", "source.description", "schedule", "neutral"),
                Set.of("schedule"), Set.of(workType), Set.of(sourceType + "-policy"),
                Set.of(sourceType + "-guard"),
                new ScheduledSourceFrontendContribution(1, "/test/schedule-source.js"));
    }

    private static ScheduledWorkRunner legacyRunner(String workType) {
        return new ScheduledWorkRunner() {
            @Override public String kind() { return workType; }
            @Override public boolean download(ScheduledWork work, ScheduledWorkSettings settings, String cookie) {
                return true;
            }
        };
    }

    private static ScheduledSourceExecutor sourceExecutor(String sourceType) {
        ScheduledSourceExecutor executor = mock(ScheduledSourceExecutor.class);
        when(executor.sourceType()).thenReturn(sourceType);
        return executor;
    }

    private static ScheduledWorkExecutor workExecutor(String workType) {
        ScheduledWorkExecutor executor = mock(ScheduledWorkExecutor.class);
        when(executor.workType()).thenReturn(workType);
        return executor;
    }

    private static ScheduledCredentialPolicy credentialPolicy(String policyId) {
        ScheduledCredentialPolicy policy = mock(ScheduledCredentialPolicy.class);
        when(policy.policyId()).thenReturn(policyId);
        return policy;
    }

    private static ScheduledExecutionGuard executionGuard(String guardId) {
        ScheduledExecutionGuard guard = mock(ScheduledExecutionGuard.class);
        when(guard.guardId()).thenReturn(guardId);
        return guard;
    }
}
