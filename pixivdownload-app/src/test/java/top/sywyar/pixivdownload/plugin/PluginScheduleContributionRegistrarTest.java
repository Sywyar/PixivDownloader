package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunnerRegistry;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkSettings;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.ScheduledSourceRegistry;

/**
 * 外置插件 schedule 贡献注册器测试：来源（来自插件元数据 {@code scheduledSources()}）+ 执行器（从子 context 发现的
 * {@link ScheduledWorkRunner} Bean）的可逆注册 / 注销、按插件原子回滚、来源接入幂等、子 context 执行器发现不含祖先。
 */
@DisplayName("外置插件 schedule 贡献注册器")
class PluginScheduleContributionRegistrarTest {

    @Test
    @DisplayName("注册插件来源后 ScheduledSourceRegistry 可按规范 type / legacy 名解析")
    void registersScheduledSource() {
        ScheduledSourceRegistry sources = emptySourceRegistry();
        ScheduledWorkRunnerRegistry runners = new ScheduledWorkRunnerRegistry(List.of());
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(sources, runners);

        registrar.register(registeredFeature("ext-a", sourceProvider("alpha", "ALPHA_LEGACY")), null);

        assertThat(sources.resolve("alpha")).isPresent();
        assertThat(sources.resolve("ALPHA_LEGACY")).isPresent();
    }

    @Test
    @DisplayName("从子 context 发现 ScheduledWorkRunner 并注册：ScheduledWorkRunnerRegistry 可按 kind 解析")
    void registersRunnerFromChildContext() {
        ScheduledSourceRegistry sources = emptySourceRegistry();
        ScheduledWorkRunnerRegistry runners = new ScheduledWorkRunnerRegistry(List.of());
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(sources, runners);

        try (AnnotationConfigApplicationContext child = childContext("alpha-kind")) {
            registrar.register(registeredFeature("ext-a"), child);

            assertThat(runners.resolve("alpha-kind")).isPresent();
            assertThat(registrar.runnerKinds("ext-a")).containsExactly("alpha-kind");
        }
    }

    @Test
    @DisplayName("unregister 后来源与执行器均不可解析")
    void unregisterRemovesSourceAndRunner() {
        ScheduledSourceRegistry sources = emptySourceRegistry();
        ScheduledWorkRunnerRegistry runners = new ScheduledWorkRunnerRegistry(List.of());
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(sources, runners);

        try (AnnotationConfigApplicationContext child = childContext("alpha-kind")) {
            registrar.register(registeredFeature("ext-a", sourceProvider("alpha", "ALPHA_LEGACY")), child);
            assertThat(sources.resolve("alpha")).isPresent();
            assertThat(runners.resolve("alpha-kind")).isPresent();

            registrar.unregister("ext-a");

            assertThat(sources.resolve("alpha")).isEmpty();
            assertThat(sources.resolve("ALPHA_LEGACY")).isEmpty();
            assertThat(runners.resolve("alpha-kind")).isEmpty();
            assertThat(registrar.runnerKinds("ext-a")).isEmpty();
        }
    }

    @Test
    @DisplayName("register → unregister → register 后快照一致（可逆，无重复注册错误）")
    void registerUnregisterRegisterIsConsistent() {
        ScheduledSourceRegistry sources = emptySourceRegistry();
        ScheduledWorkRunnerRegistry runners = new ScheduledWorkRunnerRegistry(List.of());
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(sources, runners);
        PluginRegistry.RegisteredPlugin rp = registeredFeature("ext-a", sourceProvider("alpha", "ALPHA_LEGACY"));

        try (AnnotationConfigApplicationContext c1 = childContext("alpha-kind")) {
            registrar.register(rp, c1);
        }
        registrar.unregister("ext-a");
        try (AnnotationConfigApplicationContext c2 = childContext("alpha-kind")) {
            registrar.register(rp, c2);

            assertThat(sources.resolve("alpha")).isPresent();
            assertThat(sources.resolve("ALPHA_LEGACY")).isPresent();
            assertThat(runners.resolve("alpha-kind")).isPresent();
            assertThat(registrar.runnerKinds("ext-a")).containsExactly("alpha-kind");
        }
    }

    @Test
    @DisplayName("来源 type 跨插件冲突：fail-fast，既有快照不被污染")
    void sourceTypeConflictFailsFast() {
        ScheduledSourceRegistry sources = emptySourceRegistry();
        ScheduledWorkRunnerRegistry runners = new ScheduledWorkRunnerRegistry(List.of());
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(sources, runners);

        registrar.register(registeredFeature("ext-a", sourceProvider("alpha", "A")), null);

        assertThatThrownBy(() -> registrar.register(
                registeredFeature("ext-b", sourceProvider("alpha", "B")), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate scheduled source type");
        // 既有快照不污染：alpha 仍解析到 ext-a 的来源
        assertThat(sources.sources()).hasSize(1);
        assertThat(sources.resolve("alpha")).isPresent();
    }

    @Test
    @DisplayName("执行器 kind 跨插件冲突：fail-fast，既有快照不被污染")
    void runnerKindConflictFailsFast() {
        ScheduledSourceRegistry sources = emptySourceRegistry();
        ScheduledWorkRunnerRegistry runners = new ScheduledWorkRunnerRegistry(List.of());
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(sources, runners);

        try (AnnotationConfigApplicationContext c1 = childContext("shared-kind");
             AnnotationConfigApplicationContext c2 = childContext("shared-kind")) {
            registrar.register(registeredFeature("ext-a"), c1);
            assertThat(runners.resolve("shared-kind")).isPresent();

            assertThatThrownBy(() -> registrar.register(registeredFeature("ext-b"), c2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("duplicate scheduled work runner kind");
            // 既有快照不污染：ext-a 的执行器仍在、ext-b 未记录 kind
            assertThat(runners.runners()).hasSize(1);
            assertThat(registrar.runnerKinds("ext-b")).isEmpty();
        }
    }

    @Test
    @DisplayName("执行器注册失败时已注册的来源回滚（按插件原子）")
    void runnerFailureRollsBackSource() {
        ScheduledSourceRegistry sources = emptySourceRegistry();
        // 预置一个 kind=clash 的执行器，制造插件执行器注册冲突
        ScheduledWorkRunnerRegistry runners = new ScheduledWorkRunnerRegistry(List.of(workRunner("clash")));
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(sources, runners);

        try (AnnotationConfigApplicationContext child = childContext("clash")) {
            assertThatThrownBy(() -> registrar.register(
                    registeredFeature("ext-a", sourceProvider("alpha", "A")), child))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("duplicate scheduled work runner kind");
            // 来源被回滚、不泄漏；预置执行器未被污染；未记录 ext-a 的 kind
            assertThat(sources.resolve("alpha")).isEmpty();
            assertThat(sources.sources()).isEmpty();
            assertThat(runners.runners()).hasSize(1); // 仅预置 clash
            assertThat(registrar.runnerKinds("ext-a")).isEmpty();
        }
    }

    @Test
    @DisplayName("unregister 幂等：未注册过 / 重复注销 / 空白 id 均为安全 no-op")
    void unregisterIsIdempotent() {
        ScheduledSourceRegistry sources = emptySourceRegistry();
        ScheduledWorkRunnerRegistry runners = new ScheduledWorkRunnerRegistry(List.of());
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(sources, runners);

        // 未注册过 / 空白 id：静默 no-op
        registrar.unregister("never");
        registrar.unregister(" ");
        registrar.unregister(null);

        try (AnnotationConfigApplicationContext child = childContext("alpha-kind")) {
            registrar.register(registeredFeature("ext-a", sourceProvider("alpha", "A")), child);
            registrar.unregister("ext-a");
            // 重复注销不破坏状态、不报错
            registrar.unregister("ext-a");

            assertThat(sources.resolve("alpha")).isEmpty();
            assertThat(runners.resolve("alpha-kind")).isEmpty();
        }
    }

    @Test
    @DisplayName("子 context 为 null（纯贡献插件）：仅注册来源、无执行器、不报错")
    void nullChildContextRegistersOnlySources() {
        ScheduledSourceRegistry sources = emptySourceRegistry();
        ScheduledWorkRunnerRegistry runners = new ScheduledWorkRunnerRegistry(List.of());
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(sources, runners);

        registrar.register(registeredFeature("ext-a", sourceProvider("alpha", "A")), null);

        assertThat(sources.resolve("alpha")).isPresent();
        assertThat(runners.runners()).isEmpty();
        assertThat(registrar.runnerKinds("ext-a")).isEmpty();
    }

    @Test
    @DisplayName("子 context 执行器发现不含祖先：只注册子 context 自有执行器，不误纳父 context 的内置执行器")
    void childContextRunnerDiscoveryExcludesAncestors() {
        ScheduledSourceRegistry sources = emptySourceRegistry();
        ScheduledWorkRunnerRegistry runners = new ScheduledWorkRunnerRegistry(List.of());
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(sources, runners);

        try (AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
             AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            parent.registerBean("parent-runner", ScheduledWorkRunner.class, () -> workRunner("parent-kind"));
            parent.refresh();
            child.setParent(parent);
            child.registerBean("child-runner", ScheduledWorkRunner.class, () -> workRunner("child-kind"));
            child.refresh();

            registrar.register(registeredFeature("ext-a"), child);

            assertThat(runners.resolve("child-kind")).isPresent();   // 子 context 自有执行器
            assertThat(runners.resolve("parent-kind")).isEmpty();    // 父 context 执行器未被误纳
            assertThat(registrar.runnerKinds("ext-a")).containsExactly("child-kind");
        }
    }

    @Test
    @DisplayName("来源接入幂等：来源已被构造期接入时不重复注册（仍发现并注册执行器）")
    void sourcesAlreadyRegisteredAreSkipped() {
        ScheduledSourceRegistry sources = emptySourceRegistry();
        // 模拟启动期构造期已接入 ext-a 的来源
        sources.register("ext-a", List.of(sourceProvider("alpha", "A")));
        ScheduledWorkRunnerRegistry runners = new ScheduledWorkRunnerRegistry(List.of());
        PluginScheduleContributionRegistrar registrar = new PluginScheduleContributionRegistrar(sources, runners);

        try (AnnotationConfigApplicationContext child = childContext("alpha-kind")) {
            // 不抛「already registered」；执行器照常从子 context 注册
            registrar.register(registeredFeature("ext-a", sourceProvider("alpha", "A")), child);

            assertThat(sources.resolve("alpha")).isPresent();
            assertThat(sources.sources()).hasSize(1); // 来源未被重复注册
            assertThat(runners.resolve("alpha-kind")).isPresent();
            assertThat(registrar.runnerKinds("ext-a")).containsExactly("alpha-kind");
        }
    }

    // --- 夹具 ---

    private static ScheduledSourceRegistry emptySourceRegistry() {
        return new ScheduledSourceRegistry(new PluginRegistry(List.of()));
    }

    /** 子 context：为每个给定 kind 注册一个 {@link ScheduledWorkRunner} Bean。 */
    private static AnnotationConfigApplicationContext childContext(String... runnerKinds) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        for (String kind : runnerKinds) {
            ctx.registerBean("runner-" + kind, ScheduledWorkRunner.class, () -> workRunner(kind));
        }
        ctx.refresh();
        return ctx;
    }

    private static PluginRegistry.RegisteredPlugin registeredFeature(String id, ScheduledSourceProvider... sources) {
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return id + ".label";
            }

            @Override
            public String description() {
                return id + ".summary";
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }

            @Override
            public List<ScheduledSourceProvider> scheduledSources() {
                return List.of(sources);
            }
        };
        return new PluginRegistry.RegisteredPlugin(plugin, PluginSource.EXTERNAL,
                PluginScheduleContributionRegistrarTest.class.getClassLoader());
    }

    private static ScheduledSourceProvider sourceProvider(String type, String... legacy) {
        return new ScheduledSourceProvider() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Set<String> legacyTypeNames() {
                return Set.of(legacy);
            }
        };
    }

    private static ScheduledWorkRunner workRunner(String kind) {
        return new ScheduledWorkRunner() {
            @Override
            public String kind() {
                return kind;
            }

            @Override
            public boolean download(ScheduledWork work, ScheduledWorkSettings settings, String cookie) {
                return true;
            }
        };
    }
}
