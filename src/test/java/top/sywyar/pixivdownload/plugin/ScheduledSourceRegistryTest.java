package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.schedule.ScheduledTaskType;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 计划任务来源注册中心与枚举包装测试。覆盖：内置 7 个来源对枚举值的全覆盖与规范 type 映射、
 * 仅核心贡献来源、注册可逆性、type / legacy 同类与跨类冲突即抛错、按 type / legacy 解析、
 * 失败注册整体快照不半更新、快照不可变。
 *
 * <p>这些用例固化注册中心的「身份 + legacy 映射」骨架与单快照原子发布语义，防止读侧观察到半更新、
 * 或存量 type 因跨类碰撞被静默解析到错误 provider。
 */
class ScheduledSourceRegistryTest {

    /** 7 个枚举值到规范 type 字符串的预期映射（小写短横线），同时锁死 legacy → canonical 契约。 */
    private static final Map<ScheduledTaskType, String> EXPECTED_CANONICAL = Map.of(
            ScheduledTaskType.USER_NEW, "user-new",
            ScheduledTaskType.USER_REQUEST, "user-request",
            ScheduledTaskType.SEARCH, "search",
            ScheduledTaskType.SERIES, "series",
            ScheduledTaskType.MY_BOOKMARKS, "my-bookmarks",
            ScheduledTaskType.FOLLOW_LATEST, "follow-latest",
            ScheduledTaskType.COLLECTION, "collection");

    private static ScheduledSourceRegistry emptyRegistry() {
        return new ScheduledSourceRegistry(new PluginRegistry(List.of()));
    }

    private static ScheduledSourceProvider provider(String type, String... legacy) {
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

    /**
     * 断言注册中心三套读视图（sources / byType / resolve）互相一致：每条来源的规范 type 与各 legacy 名
     * 都解析回同一个 provider 实例。验证快照是整体一致发布——无孤儿索引项、也无缺失项。
     */
    private static void assertSnapshotConsistent(ScheduledSourceRegistry registry) {
        for (ScheduledSourceRegistry.RegisteredSource source : registry.sources()) {
            ScheduledSourceProvider provider = source.provider();
            assertThat(registry.byType(provider.type()))
                    .as("规范 type %s 应在 byType 索引解析到同一 provider", provider.type())
                    .containsSame(provider);
            assertThat(registry.resolve(provider.type()))
                    .as("规范 type %s 应可被 resolve", provider.type())
                    .containsSame(provider);
            for (String legacy : provider.legacyTypeNames()) {
                assertThat(registry.resolve(legacy))
                        .as("legacy 名 %s 应 resolve 到同一 provider", legacy)
                        .containsSame(provider);
            }
        }
    }

    @Test
    @DisplayName("内置来源覆盖全部 ScheduledTaskType 枚举值：legacy 枚举名与规范 type 均可解析到对应 provider")
    void builtInSourcesCoverEveryEnumValue() {
        ScheduledSourceRegistry registry = ScheduledSourceRegistry.forBuiltInPlugins();

        assertThat(registry.sources()).hasSize(ScheduledTaskType.values().length);
        for (ScheduledTaskType type : ScheduledTaskType.values()) {
            String canonical = EXPECTED_CANONICAL.get(type);
            // legacy 枚举名（数据库 type 列现存值）→ provider
            assertThat(registry.resolve(type.name()))
                    .as("legacy 类型 %s 应解析到 provider", type.name())
                    .isPresent();
            // 规范 type（小写短横线）→ 同一 provider
            assertThat(registry.byType(canonical))
                    .as("规范 type %s 应解析到 provider", canonical)
                    .isPresent();
            assertThat(registry.resolve(type.name()).orElseThrow().type()).isEqualTo(canonical);
            assertThat(registry.byType(canonical).orElseThrow().legacyTypeNames()).contains(type.name());
        }
    }

    @Test
    @DisplayName("内置插件中仅核心插件贡献计划任务来源，且恰好 7 个")
    void onlyCorePluginContributesScheduledSources() {
        for (PixivFeaturePlugin plugin : BuiltInPlugins.createAll()) {
            if (plugin.id().equals("core")) {
                assertThat(plugin.scheduledSources())
                        .as("核心插件应贡献全部 7 个计划任务来源")
                        .hasSize(ScheduledTaskType.values().length);
            } else {
                assertThat(plugin.scheduledSources())
                        .as("功能插件 %s 当前不贡献计划任务来源", plugin.id())
                        .isEmpty();
            }
        }
    }

    @Test
    @DisplayName("注册一个插件的全部来源后可按规范 type 与 legacy 名解析")
    void registerThenResolve() {
        ScheduledSourceRegistry registry = emptyRegistry();
        registry.register("plugin-a", List.of(provider("alpha", "ALPHA_LEGACY")));

        assertThat(registry.byType("alpha")).isPresent();
        assertThat(registry.resolve("alpha")).isPresent();
        assertThat(registry.resolve("ALPHA_LEGACY")).isPresent();
        assertThat(registry.resolve("ALPHA_LEGACY").orElseThrow().type()).isEqualTo("alpha");
        // 空 / 未知一律 empty，不抛错
        assertThat(registry.byType("unknown")).isEmpty();
        assertThat(registry.resolve("unknown")).isEmpty();
        assertThat(registry.resolve(null)).isEmpty();
        assertThat(registry.byType(" ")).isEmpty();
    }

    @Test
    @DisplayName("同一 pluginId 重复注册即抛错（应用启动失败而非带病运行）")
    void duplicatePluginRegistrationRejected() {
        ScheduledSourceRegistry registry = emptyRegistry();
        registry.register("plugin-a", List.of(provider("alpha", "A")));
        assertThatThrownBy(() -> registry.register("plugin-a", List.of(provider("beta", "B"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    @DisplayName("规范 type 跨插件冲突即抛错")
    void duplicateCanonicalTypeRejected() {
        ScheduledSourceRegistry registry = emptyRegistry();
        registry.register("plugin-a", List.of(provider("alpha", "A")));
        assertThatThrownBy(() -> registry.register("plugin-b", List.of(provider("alpha", "B"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate scheduled source type");
    }

    @Test
    @DisplayName("legacy 类型名跨插件冲突即抛错")
    void duplicateLegacyTypeRejected() {
        ScheduledSourceRegistry registry = emptyRegistry();
        registry.register("plugin-a", List.of(provider("alpha", "SHARED")));
        assertThatThrownBy(() -> registry.register("plugin-b", List.of(provider("beta", "SHARED"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate scheduled source legacy type");
    }

    @Test
    @DisplayName("同一批次内规范 type 冲突即抛错，且失败时不污染已有快照与索引")
    void duplicateWithinSameBatchRejectedAndStateUnchanged() {
        ScheduledSourceRegistry registry = emptyRegistry();
        registry.register("plugin-a", List.of(provider("alpha", "A")));
        assertThatThrownBy(() -> registry.register("plugin-b",
                List.of(provider("dup", "X"), provider("dup", "Y"))))
                .isInstanceOf(IllegalStateException.class);
        // plugin-b 整体未生效：快照仍只含 plugin-a，索引未引入 dup / X / Y
        assertThat(registry.sources()).hasSize(1);
        assertThat(registry.sources()).allSatisfy(s -> assertThat(s.pluginId()).isEqualTo("plugin-a"));
        assertThat(registry.byType("dup")).isEmpty();
        assertThat(registry.resolve("X")).isEmpty();
    }

    @Test
    @DisplayName("新来源规范 type 撞旧来源 legacy 名即抛错，且不抢占既有 legacy 解析")
    void canonicalTypeClashingExistingLegacyRejected() {
        ScheduledSourceRegistry registry = emptyRegistry();
        registry.register("plugin-a", List.of(provider("alpha", "SHARED")));

        assertThatThrownBy(() -> registry.register("plugin-b", List.of(provider("SHARED", "B"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate scheduled source type");

        // 关键防回归：SHARED 仍只作为 plugin-a 的 legacy 名解析，未被 plugin-b 的规范 type 抢占
        assertThat(registry.byType("SHARED")).isEmpty();
        assertThat(registry.resolve("SHARED").orElseThrow().type()).isEqualTo("alpha");
        assertThat(registry.sources()).hasSize(1);
        assertSnapshotConsistent(registry);
    }

    @Test
    @DisplayName("新来源 legacy 名撞旧来源规范 type 即抛错，且不污染既有规范解析")
    void legacyTypeClashingExistingCanonicalRejected() {
        ScheduledSourceRegistry registry = emptyRegistry();
        registry.register("plugin-a", List.of(provider("alpha", "A")));

        assertThatThrownBy(() -> registry.register("plugin-b", List.of(provider("beta", "alpha"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate scheduled source legacy type");

        assertThat(registry.byType("beta")).isEmpty();
        // alpha 仍是 plugin-a 的规范 type，未被 plugin-b 的 legacy 名污染
        assertThat(registry.resolve("alpha").orElseThrow().type()).isEqualTo("alpha");
        assertThat(registry.sources()).hasSize(1);
        assertSnapshotConsistent(registry);
    }

    @Test
    @DisplayName("同一批次内规范 type 与 legacy 名跨类冲突即抛错并整体回滚")
    void crossConflictWithinSameBatchRejectedAndStateUnchanged() {
        ScheduledSourceRegistry registry = emptyRegistry();
        registry.register("plugin-a", List.of(provider("alpha", "A")));

        // 同批：第二个 provider 把第一个 provider 的规范 type "gamma" 当作 legacy 名声明
        assertThatThrownBy(() -> registry.register("plugin-c",
                List.of(provider("gamma", "G"), provider("delta", "gamma"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate scheduled source legacy type");

        // plugin-c 整体未生效：gamma / delta / G 均未混入任何索引
        assertThat(registry.sources()).hasSize(1);
        assertThat(registry.byType("gamma")).isEmpty();
        assertThat(registry.byType("delta")).isEmpty();
        assertThat(registry.resolve("gamma")).isEmpty();
        assertThat(registry.resolve("G")).isEmpty();
        // plugin-a 完好
        assertThat(registry.resolve("A").orElseThrow().type()).isEqualTo("alpha");
        assertSnapshotConsistent(registry);
    }

    @Test
    @DisplayName("失败注册不半更新整体快照：失败批次中排在冲突项之前的合法来源也不泄漏")
    void failedRegistrationDoesNotHalfUpdateSnapshot() {
        ScheduledSourceRegistry registry = emptyRegistry();
        registry.register("plugin-a", List.of(provider("alpha", "A")));

        // 失败批次：先一个完全不冲突的来源，再一个撞 plugin-a 规范 type 的来源
        assertThatThrownBy(() -> registry.register("plugin-b",
                List.of(provider("fresh-canon", "FRESH_LEG"), provider("alpha", "DUP"))))
                .isInstanceOf(IllegalStateException.class);

        // 整批回滚：连排在冲突项之前、本身合法的 fresh-canon / FRESH_LEG 也未泄漏到任何索引
        assertThat(registry.sources()).hasSize(1);
        assertThat(registry.byType("fresh-canon")).isEmpty();
        assertThat(registry.resolve("fresh-canon")).isEmpty();
        assertThat(registry.resolve("FRESH_LEG")).isEmpty();
        assertThat(registry.resolve("DUP")).isEmpty();
        // 既有来源三视图仍整体一致
        assertSnapshotConsistent(registry);
        assertThat(registry.resolve("A").orElseThrow().type()).isEqualTo("alpha");
    }

    @Test
    @DisplayName("注销后注册中心状态与该插件从未注册过一致（可逆）")
    void unregisterIsReversible() {
        ScheduledSourceRegistry registry = emptyRegistry();
        registry.register("plugin-a", List.of(provider("alpha", "A")));
        registry.register("plugin-b", List.of(provider("beta", "B")));

        registry.unregister("plugin-a");
        assertThat(registry.sources()).hasSize(1);
        assertThat(registry.byType("alpha")).isEmpty();
        assertThat(registry.resolve("A")).isEmpty();
        assertThat(registry.byType("beta")).isPresent();

        // 注销后可以用同样的 type 重新注册（说明索引已彻底清除）
        registry.register("plugin-a", List.of(provider("alpha", "A")));
        assertThat(registry.byType("alpha")).isPresent();

        // 未注册过的 pluginId 注销静默返回
        registry.unregister("never-registered");
        assertThat(registry.sources()).hasSize(2);
    }

    @Test
    @DisplayName("空来源 / 空 pluginId 注册即抛错")
    void invalidRegistrationRejected() {
        ScheduledSourceRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register("plugin-a", List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register(" ", List.of(provider("alpha", "A"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("sources() 返回不可变快照")
    void sourcesSnapshotIsImmutable() {
        ScheduledSourceRegistry registry = emptyRegistry();
        registry.register("plugin-a", List.of(provider("alpha", "A")));
        List<ScheduledSourceRegistry.RegisteredSource> snapshot = registry.sources();
        assertThatThrownBy(() -> snapshot.add(new ScheduledSourceRegistry.RegisteredSource(
                "plugin-x", provider("x", "X"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
