package top.sywyar.pixivdownload.core.schedule.capability;

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
@DisplayName("外置计划能力注册器原子发布")
class PluginScheduleContributionRegistrarPublicationTest extends PluginScheduleContributionRegistrarTestSupport {

    @Test
    @DisplayName("一次发布来源描述符与 child context 四类行为 Bean，并由宿主盖章 owner、package 和 generation")
    void publishesCompleteOwnerBundle() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);

        try (AnnotationConfigApplicationContext child = completeChildContext("alpha", "alpha-work")) {
            ScheduleCapabilityPublication publication = register(registrar,
                    registeredFeature("ext-a", "ext-a", 7L,
                            List.of(sourceDescriptor("alpha", "alpha-work", "ALPHA"))), child).orElseThrow();

            assertThat(publication.owner()).isEqualTo(
                    new ScheduleCapabilityOwner("ext-a", "ext-a", 7L));
            assertThat(registry.snapshotView().owners()).singleElement().satisfies(owner -> {
                assertThat(owner.owner()).isEqualTo(publication.owner());
                assertThat(owner.sourceTypes()).containsExactly("alpha");
                assertThat(owner.sourceAliases()).containsExactly("ALPHA");
                assertThat(owner.workTypes()).containsExactly("alpha-work");
                assertThat(owner.credentialPolicyIds()).containsExactly("alpha-policy");
                assertThat(owner.guardIds()).containsExactly("alpha-guard");
            });
            assertThat(registry.resolveSourceDescriptor("ALPHA")).isPresent();
            assertThat(registry.resolveSourceExecutor("ALPHA")).isPresent();
            assertThat(registry.resolveWorkExecutor("alpha-work")).isPresent();
            assertThat(registry.resolveCredentialPolicy("alpha-policy")).isPresent();
            assertThat(registry.resolveGuard("alpha-guard")).isPresent();
        }
    }

    @Test
    @DisplayName("插件 getter 或 bundle 校验失败时旧 snapshot 完整保留，不产生 publication")
    void preparationFailureDoesNotPolluteSnapshot() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);
        registerModern(registrar, registeredFeature("ext-a", "ext-a", 1L,
                List.of(sourceDescriptor("stable", "stable-work", "STABLE")))).orElseThrow();
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

        assertThatThrownBy(() -> register(registrar, registered, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduledSourceDescriptors")
                .hasMessageContaining("IllegalStateException")
                .hasMessageNotContaining("broken getter")
                .hasNoCause();
        assertThat(registry.snapshotView().revision()).isEqualTo(revision);
        assertThat(registry.resolveSourceDescriptor("stable")).isPresent();
        assertThat(ScheduleCapabilityRegistryTestAccess.publication(
                registrar, new ScheduleCapabilityOwner("ext-b", "ext-b", 2L))).isEmpty();
    }

    @Test
    @DisplayName("插件贡献 getter 抛断言错误时无 cause 且 schedule 快照保持不变")
    void contributionGetterAssertionErrorIsNormalizedAtBoundary() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);
        AtomicInteger reads = new AtomicInteger();
        var before = registry.snapshotView();
        PixivFeaturePlugin broken = new PixivFeaturePlugin() {
            @Override public String id() { return "ext-assert"; }
            @Override public String displayName() { return "ext-assert.label"; }
            @Override public String description() { return "ext-assert.summary"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<ScheduledSourceDescriptor> scheduledSourceDescriptors() {
                reads.incrementAndGet();
                throw new AssertionError("plugin-private-getter-failure");
            }
        };
        PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                broken,
                PluginSource.EXTERNAL,
                getClass().getClassLoader(),
                "ext-assert",
                1L);

        assertThatThrownBy(() -> register(registrar, registered, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduledSourceDescriptors")
                .hasMessageContaining("AssertionError")
                .hasMessageNotContaining("plugin-private-getter-failure")
                .hasNoCause();
        assertThat(reads).hasValue(1);
        assertThat(registry.snapshotView()).isEqualTo(before);
    }

    @Test
    @DisplayName("child Bean 能力 getter 抛断言错误时无 cause 且旧 owner 快照不变")
    void childCapabilityGetterAssertionErrorIsNormalizedAtBoundary() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);
        registerModern(registrar, registeredFeature(
                "stable-owner", "stable-owner", 1L,
                List.of(sourceDescriptor("stable-source", "stable-work")))).orElseThrow();
        var before = registry.snapshotView();
        ScheduledSourceExecutor brokenExecutor = mock(ScheduledSourceExecutor.class);
        when(brokenExecutor.sourceType()).thenThrow(new AssertionError("plugin-private-source-type"));

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.registerBean("broken-source-executor", ScheduledSourceExecutor.class,
                    () -> brokenExecutor);
            child.refresh();

            assertThatThrownBy(() -> register(
                    registrar,
                    registeredFeature(
                            "broken-owner", "broken-owner", 2L,
                            List.of()),
                    child))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("source executor type")
                    .hasMessageContaining("AssertionError")
                    .hasMessageNotContaining("plugin-private-source-type")
                    .hasNoCause();
        }

        assertThat(registry.snapshotView()).isEqualTo(before);
        assertThat(ScheduleCapabilityRegistryTestAccess.publication(
                registrar,
                new ScheduleCapabilityOwner("broken-owner", "broken-owner", 2L))).isEmpty();
    }

    @Test
    @DisplayName("child context Bean 发现不含父 context，并在 descriptor/executor 不匹配时原子拒绝")
    void childBeanDiscoveryExcludesAncestors() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);
        ScheduledSourceExecutor parentExecutor = sourceExecutor("parent");

        try (AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
             AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            parent.registerBean("parent-source", ScheduledSourceExecutor.class, () -> parentExecutor);
            parent.refresh();
            child.setParent(parent);
            registerCompleteBeans(child, "alpha", "alpha-work");
            child.refresh();

            assertThat(register(registrar, registeredFeature(
                    "ext-a", "ext-a", 5L,
                    List.of(sourceDescriptor("alpha", "alpha-work"))), child)).isPresent();
            assertThat(registry.resolveSourceExecutor("alpha")).isPresent();
            assertThat(registry.resolveSourceExecutor("parent")).isEmpty();
        }
    }

    @Test
    @DisplayName("无任何 schedule 能力的插件返回空 publication 且不改变 registry")
    void emptyPluginIsTransparent() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        PluginScheduleContributionRegistrar registrar = registrar(registry);

        Optional<ScheduleCapabilityPublication> publication = register(registrar,
                registeredFeature("ext-a", "ext-a", 0L, List.of()), null);

        assertThat(publication).isEmpty();
        assertThat(registry.snapshotView().owners()).isEmpty();
    }

}
