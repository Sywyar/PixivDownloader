package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.QueueTypeRegistry;

class PluginRegistryTest {

    private static class TestPlugin implements PixivFeaturePlugin {

        private final String id;
        private final PluginKind kind;
        private final List<String> lifecycleLog;
        private final boolean failOnStop;

        TestPlugin(String id) {
            this(id, PluginKind.FEATURE);
        }

        TestPlugin(String id, PluginKind kind) {
            this(id, kind, new ArrayList<>(), false);
        }

        TestPlugin(String id, List<String> lifecycleLog, boolean failOnStop) {
            this(id, PluginKind.FEATURE, lifecycleLog, failOnStop);
        }

        TestPlugin(String id, PluginKind kind, List<String> lifecycleLog, boolean failOnStop) {
            this.id = id;
            this.kind = kind;
            this.lifecycleLog = lifecycleLog;
            this.failOnStop = failOnStop;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return "plugin.label";
        }

        @Override
        public String description() {
            return "plugin.summary";
        }

        @Override
        public PluginKind kind() {
            return kind;
        }

        @Override
        public boolean required() {
            return false;
        }

        @Override
        public void start() {
            lifecycleLog.add("start:" + id);
        }

        @Override
        public void stop() {
            lifecycleLog.add("stop:" + id);
            if (failOnStop) {
                throw new RuntimeException("boom");
            }
        }
    }

    @Test
    @DisplayName("插件 id 重复时构造失败")
    void duplicateIdFailsConstruction() {
        assertThatThrownBy(() -> new PluginRegistry(List.of(new TestPlugin("stats"), new TestPlugin("stats"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate plugin id");
    }

    @Test
    @DisplayName("不符合小写短横线规范的插件 id 被拒绝")
    void invalidIdRejected() {
        for (String invalid : new String[]{"Stats", "stats_page", "-stats", "stats-", "stats--page", "1stats"}) {
            assertThatThrownBy(() -> new PluginRegistry(List.of(new TestPlugin(invalid))))
                    .as("id: %s", invalid)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("invalid plugin id");
        }
    }

    @Test
    @DisplayName("空字符串插件 id 被拒绝")
    void blankIdRejected() {
        assertThatThrownBy(() -> new PluginRegistry(List.of(new TestPlugin(""))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("register→unregister→再 register 后快照与首次注册一致")
    void reregisterRestoresSnapshot() {
        TestPlugin stats = new TestPlugin("stats");
        PluginRegistry registry = new PluginRegistry(List.of(new TestPlugin("core"), stats));
        List<PixivFeaturePlugin> initial = registry.plugins();

        registry.unregister("stats");
        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).containsExactly("core");
        assertThat(registry.find("stats")).isEmpty();

        registry.register(stats);
        assertThat(registry.plugins()).isEqualTo(initial);
        assertThat(registry.find("stats")).contains(stats);
    }

    @Test
    @DisplayName("注销未注册的插件 id 抛出异常")
    void unregisterUnknownIdThrows() {
        PluginRegistry registry = new PluginRegistry(List.of(new TestPlugin("core")));

        assertThatThrownBy(() -> registry.unregister("stats"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown plugin id");
    }

    @Test
    @DisplayName("快照按注册顺序排列且不可变")
    void snapshotIsOrderedAndImmutable() {
        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core"), new TestPlugin("gallery"), new TestPlugin("stats")));

        List<PixivFeaturePlugin> snapshot = registry.plugins();
        assertThat(snapshot).extracting(PixivFeaturePlugin::id).containsExactly("core", "gallery", "stats");
        assertThatThrownBy(() -> snapshot.add(new TestPlugin("novel")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("start 按注册顺序调用各插件，stop 按反序调用且单个失败不中断")
    void lifecycleOrderAndFailureIsolation() {
        List<String> lifecycleLog = new ArrayList<>();
        PluginRegistry registry = new PluginRegistry(List.of(
                new TestPlugin("core", lifecycleLog, false),
                new TestPlugin("gallery", lifecycleLog, true),
                new TestPlugin("stats", lifecycleLog, false)));

        registry.start();
        assertThat(registry.isRunning()).isTrue();
        assertThat(lifecycleLog).containsExactly("start:core", "start:gallery", "start:stats");

        lifecycleLog.clear();
        registry.stop();
        assertThat(registry.isRunning()).isFalse();
        assertThat(lifecycleLog).containsExactly("stop:stats", "stop:gallery", "stop:core");
    }

    @Test
    @DisplayName("RegisteredPlugin 构造后不再回调可变 plugin id getter")
    void registeredPluginCapturesStableIdOnce() {
        FlakyIdPlugin plugin = new FlakyIdPlugin("flaky-owner");
        PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, plugin.getClass().getClassLoader(), "flaky-owner", 1L);
        PluginRegistry registry = new PluginRegistry(List.of());

        registry.register(registered);

        assertThat(plugin.idCalls()).isEqualTo(1);
        assertThat(registry.registeredPlugins()).containsExactly(registered);
        assertThat(registry.allPlugins()).containsExactly(plugin);
    }

    @Test
    @DisplayName("下游贡献注册使用捕获的稳定 owner id 而不再次调用插件 getter")
    void downstreamRegistryUsesCapturedOwnerId() {
        FlakyQueuePlugin plugin = new FlakyQueuePlugin("flaky-owner");
        PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, plugin.getClass().getClassLoader());
        PluginRegistry registry = new PluginRegistry(List.of());
        registry.register(registered);

        QueueTypeRegistry queueTypes = new QueueTypeRegistry(registry);

        assertThat(plugin.idCalls()).isEqualTo(1);
        assertThat(queueTypes.queueTypes()).singleElement().satisfies(queue -> {
            assertThat(queue.pluginId()).isEqualTo("flaky-owner");
            assertThat(queue.queueType().type()).isEqualTo("flaky-type");
        });
    }

    @Test
    @DisplayName("register 准备活动状态失败时安装态与活动态均不发布")
    void registerPreparationFailurePublishesNoPrefixState() {
        ThrowingToggleProperties toggles = new ThrowingToggleProperties();
        PluginRegistry registry = new PluginRegistry(List.of(), toggles);
        TestPlugin plugin = new TestPlugin("atomic-owner");
        PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, plugin.getClass().getClassLoader());
        AssertionError failure = new AssertionError("toggle lookup failed");
        toggles.failure = failure;

        assertThatThrownBy(() -> registry.register(registered)).isSameAs(failure);

        assertThat(registry.containsIdentity(registered)).isFalse();
        assertThat(registry.registeredPlugins()).isEmpty();
        assertThat(registry.allPlugins()).isEmpty();
    }

    @Test
    @DisplayName("外置注册身份要求包 id 与稳定 feature id 一致")
    void externalIdentityRequiresMatchingPackageId() {
        TestPlugin plugin = new TestPlugin("feature-owner");

        assertThatThrownBy(() -> new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, plugin.getClass().getClassLoader(), "another-package", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("packageId must match");
    }

    @Test
    @DisplayName("注册身份拒绝负 generation")
    void registeredIdentityRejectsNegativeGeneration() {
        TestPlugin plugin = new TestPlugin("feature-owner");

        assertThatThrownBy(() -> new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, plugin.getClass().getClassLoader(), "feature-owner", -1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    @DisplayName("禁用的功能插件不进入活动快照，但仍保留在 allPlugins 并出现在 disabledPlugins")
    void disabledFeaturePluginExcludedFromActiveSnapshotButKeptInstalled() {
        PluginToggleProperties toggles = new PluginToggleProperties();
        toggles.put("stats", disabledToggle());
        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE),
                        new TestPlugin("gallery"),
                        new TestPlugin("stats")),
                toggles);

        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).containsExactly("core", "gallery");
        assertThat(registry.allPlugins()).extracting(PixivFeaturePlugin::id)
                .containsExactly("core", "gallery", "stats");
        assertThat(registry.disabledPlugins()).extracting(PixivFeaturePlugin::id).containsExactly("stats");
        assertThat(registry.find("stats")).isEmpty();
    }

    @Test
    @DisplayName("核心插件即使配置 enabled=false 也不可禁用")
    void corePluginCannotBeDisabled() {
        PluginToggleProperties toggles = new PluginToggleProperties();
        toggles.put("core", disabledToggle());
        toggles.put("stats", disabledToggle());
        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE), new TestPlugin("stats")),
                toggles);

        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).containsExactly("core");
        assertThat(registry.disabledPlugins()).extracting(PixivFeaturePlugin::id).containsExactly("stats");
    }

    @Test
    @DisplayName("外置插件自称 required 不得绕过 enabled=false")
    void externalSelfDeclaredRequiredCannotBypassDisabledToggle() {
        ClassLoader extCl = new ClassLoader(getClass().getClassLoader()) {};
        PluginToggleProperties toggles = new PluginToggleProperties();
        toggles.put("third-party", disabledToggle());
        PluginDiscoveryResult discovery = new PluginDiscoveryResult(
                List.of(external("third-party", new RequiredTestPlugin("third-party"), extCl)), List.of());

        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE)), toggles, discovery);

        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).containsExactly("core");
        assertThat(registry.allPlugins()).extracting(PixivFeaturePlugin::id).contains("third-party");
        assertThat(registry.disabledPlugins()).extracting(PixivFeaturePlugin::id).containsExactly("third-party");
        assertThat(registry.source("third-party")).isEmpty();
    }

    @Test
    @DisplayName("核心 RequiredPluginPolicy 声明的外置插件可覆盖 enabled=false")
    void requiredPolicyCanKeepExternalPluginActiveWhenToggleDisabled() {
        ClassLoader extCl = new ClassLoader(getClass().getClassLoader()) {};
        PluginToggleProperties toggles = new PluginToggleProperties();
        toggles.put("download-workbench", disabledToggle());
        PluginDiscoveryResult discovery = new PluginDiscoveryResult(
                List.of(external("download-workbench", new TestPlugin("download-workbench"), extCl)), List.of());
        RequiredPluginPolicy policy = RequiredPluginPolicy.of(List.of(new RequiredPluginPolicy.RequiredPlugin(
                "download-workbench", PluginApiRequirement.unspecified(), false, "plugin.required.missing")));

        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE)), toggles, discovery, policy);

        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id)
                .containsExactly("core", "download-workbench");
        assertThat(registry.disabledPlugins()).isEmpty();
        assertThat(registry.source("download-workbench")).contains(PluginSource.EXTERNAL);
    }

    @Test
    @DisplayName("禁用插件不进入活动快照，其生命周期方法不被调用")
    void disabledPluginLifecycleNotInvoked() {
        List<String> log = new ArrayList<>();
        PluginToggleProperties toggles = new PluginToggleProperties();
        toggles.put("stats", disabledToggle());
        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE, log, false),
                        new TestPlugin("stats", PluginKind.FEATURE, log, false)),
                toggles);

        registry.start();
        assertThat(log).containsExactly("start:core");

        log.clear();
        registry.stop();
        assertThat(log).containsExactly("stop:core");
    }

    // ---- 双来源（内置 + 外置）----

    private static DiscoveredFeaturePlugin external(String sourcePackageId, PixivFeaturePlugin plugin,
                                                    ClassLoader classLoader) {
        return new DiscoveredFeaturePlugin(sourcePackageId, plugin, classLoader);
    }

    @Test
    @DisplayName("内置 + 外置同时注册：内置在前、外置按 id 排序在后，来源信息可区分")
    void builtInAndExternalRegisterTogetherWithStableOrderAndSource() {
        ClassLoader extCl = new ClassLoader(getClass().getClassLoader()) {};
        PluginDiscoveryResult discovery = new PluginDiscoveryResult(List.of(
                external("ext-zeta", new TestPlugin("ext-zeta"), extCl),
                external("ext-alpha", new TestPlugin("ext-alpha"), extCl)), List.of());

        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE), new TestPlugin("gallery")),
                new PluginToggleProperties(), discovery);

        // 内置保持装配顺序在前，外置按 feature id 排序追加在后
        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id)
                .containsExactly("core", "gallery", "ext-alpha", "ext-zeta");
        assertThat(registry.source("core")).contains(PluginSource.BUILT_IN);
        assertThat(registry.source("gallery")).contains(PluginSource.BUILT_IN);
        assertThat(registry.source("ext-alpha")).contains(PluginSource.EXTERNAL);
        assertThat(registry.source("ext-zeta")).contains(PluginSource.EXTERNAL);
        // 外置插件也进入 allPlugins（schema 合并覆盖外置）
        assertThat(registry.allPlugins()).extracting(PixivFeaturePlugin::id)
                .contains("ext-alpha", "ext-zeta");
    }

    @Test
    @DisplayName("外置插件携带其插件 classloader，内置插件用应用 classloader")
    void externalPluginCarriesItsOwnClassLoader() {
        ClassLoader extCl = new ClassLoader(getClass().getClassLoader()) {};
        TestPlugin extPlugin = new TestPlugin("ext-stats");
        PluginDiscoveryResult discovery = new PluginDiscoveryResult(
                List.of(external("ext-stats", extPlugin, extCl)), List.of());

        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE)), new PluginToggleProperties(), discovery);

        PluginRegistry.RegisteredPlugin ext = registry.registeredPlugins().stream()
                .filter(rp -> rp.id().equals("ext-stats")).findFirst().orElseThrow();
        // registry 保留发现桥接给出的插件 classloader（而非 extPlugin.getClass() 的 app loader）
        assertThat(ext.classLoader()).isSameAs(extCl);
        PluginRegistry.RegisteredPlugin core = registry.registeredPlugins().stream()
                .filter(rp -> rp.id().equals("core")).findFirst().orElseThrow();
        assertThat(core.classLoader()).isSameAs(TestPlugin.class.getClassLoader());
    }

    @Test
    @DisplayName("外置 pluginId 与内置冲突：构造期 fail-fast 并指出冲突双方来源")
    void externalIdConflictingWithBuiltInFailsFast() {
        PluginDiscoveryResult discovery = new PluginDiscoveryResult(
                List.of(external("gallery", new TestPlugin("gallery"),
                        new ClassLoader(getClass().getClassLoader()) {})), List.of());

        assertThatThrownBy(() -> new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE), new TestPlugin("gallery")),
                new PluginToggleProperties(), discovery))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate plugin id 'gallery'")
                .hasMessageContaining("BUILT_IN")
                .hasMessageContaining("external plugin package 'gallery'");
    }

    @Test
    @DisplayName("两个外置插件 id 冲突：构造期 fail-fast")
    void twoExternalsWithSameIdFailFast() {
        ClassLoader cl = new ClassLoader(getClass().getClassLoader()) {};
        PluginDiscoveryResult discovery = new PluginDiscoveryResult(List.of(
                external("ext-dup", new TestPlugin("ext-dup"), cl),
                external("ext-dup", new TestPlugin("ext-dup"), cl)), List.of());

        assertThatThrownBy(() -> new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE)), new PluginToggleProperties(), discovery))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate plugin id 'ext-dup'");
    }

    @Test
    @DisplayName("外置插件 register→unregister→再 register 可逆：plugins/registeredPlugins/allPlugins/source/classloader 全恢复")
    void externalRegisterUnregisterReregisterIsReversible() {
        ClassLoader extCl = new ClassLoader(getClass().getClassLoader()) {};
        TestPlugin extPlugin = new TestPlugin("ext-stats");
        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE)), new PluginToggleProperties(),
                new PluginDiscoveryResult(List.of(external("ext-stats", extPlugin, extCl)), List.of()));
        List<PixivFeaturePlugin> initial = registry.plugins();
        List<PixivFeaturePlugin> initialAll = registry.allPlugins();
        List<PluginRegistry.RegisteredPlugin> initialRegistered = registry.registeredPlugins();

        registry.unregister("ext-stats");
        // 安装态与活动快照同时移除：四个读视图都不再含外置插件，与从未注册过一致
        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).containsExactly("core");
        assertThat(registry.registeredPlugins()).extracting(PluginRegistry.RegisteredPlugin::id)
                .containsExactly("core");
        assertThat(registry.allPlugins()).extracting(PixivFeaturePlugin::id).containsExactly("core");
        assertThat(registry.disabledPlugins()).isEmpty();
        assertThat(registry.find("ext-stats")).isEmpty();
        assertThat(registry.source("ext-stats")).isEmpty();

        registry.register(extPlugin, PluginSource.EXTERNAL, extCl);
        assertThat(registry.plugins()).isEqualTo(initial);
        assertThat(registry.allPlugins()).isEqualTo(initialAll);
        assertThat(registry.registeredPlugins())
                .usingRecursiveComparison()
                .ignoringFields("packageId", "generation")
                .isEqualTo(initialRegistered);
        assertThat(registry.source("ext-stats")).contains(PluginSource.EXTERNAL);
        assertThat(registry.registeredPlugins().stream()
                .filter(rp -> rp.id().equals("ext-stats")).findFirst().orElseThrow().classLoader())
                .isSameAs(extCl);
    }

    @Test
    @DisplayName("注销外置插件后 allPlugins 不再包含它（schema 合并不再覆盖该插件）")
    void unregisterExternalDropsFromAllPlugins() {
        ClassLoader extCl = new ClassLoader(getClass().getClassLoader()) {};
        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE)), new PluginToggleProperties(),
                new PluginDiscoveryResult(List.of(external("ext-stats", new TestPlugin("ext-stats"), extCl)),
                        List.of()));
        assertThat(registry.allPlugins()).extracting(PixivFeaturePlugin::id).contains("ext-stats");

        registry.unregister("ext-stats");

        assertThat(registry.allPlugins()).extracting(PixivFeaturePlugin::id).containsExactly("core");
        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).containsExactly("core");
        assertThat(registry.disabledPlugins()).isEmpty();
    }

    @Test
    @DisplayName("注销禁用（安装但未启用）的插件：从 allPlugins 与 disabledPlugins 一并移除")
    void unregisterDisabledPluginDropsFromInstalled() {
        PluginToggleProperties toggles = new PluginToggleProperties();
        toggles.put("stats", disabledToggle());
        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE), new TestPlugin("stats")), toggles);
        // 禁用插件不在活动快照、但保留在 allPlugins / disabledPlugins
        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).containsExactly("core");
        assertThat(registry.allPlugins()).extracting(PixivFeaturePlugin::id).containsExactly("core", "stats");
        assertThat(registry.disabledPlugins()).extracting(PixivFeaturePlugin::id).containsExactly("stats");

        // 安装态含该 id（活动快照不含），仍可注销
        registry.unregister("stats");
        assertThat(registry.allPlugins()).extracting(PixivFeaturePlugin::id).containsExactly("core");
        assertThat(registry.disabledPlugins()).isEmpty();
    }

    @Test
    @DisplayName("外置发现失败诊断不致命：成功发现的外置插件照常注册")
    void externalDiscoveryFailuresAreNonFatal() {
        ClassLoader extCl = new ClassLoader(getClass().getClassLoader()) {};
        PluginDiscoveryResult discovery = new PluginDiscoveryResult(
                List.of(external("ext-good", new TestPlugin("ext-good"), extCl)),
                List.of(new PluginLoadFailure("broken-pack", "does not implement PixivPluginProvider")));

        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE)), new PluginToggleProperties(), discovery);

        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id)
                .containsExactly("core", "ext-good");
    }

    private static PluginToggleProperties.PluginToggle disabledToggle() {
        PluginToggleProperties.PluginToggle toggle = new PluginToggleProperties.PluginToggle();
        toggle.setEnabled(false);
        return toggle;
    }

    private static final class RequiredTestPlugin extends TestPlugin {
        private RequiredTestPlugin(String id) {
            super(id);
        }

        @Override
        public boolean required() {
            return true;
        }
    }

    private static final class FlakyIdPlugin implements PixivFeaturePlugin {
        private final String id;
        private int idCalls;

        private FlakyIdPlugin(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            if (++idCalls > 1) {
                throw new AssertionError("plugin id getter must not be called again");
            }
            return id;
        }

        @Override public String displayName() { return "plugin.label"; }
        @Override public String description() { return "plugin.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }

        int idCalls() {
            return idCalls;
        }
    }

    private static final class ThrowingToggleProperties extends PluginToggleProperties {
        private AssertionError failure;

        @Override
        public boolean isEnabled(String pluginId) {
            if (failure != null) {
                throw failure;
            }
            return super.isEnabled(pluginId);
        }
    }

    private static final class FlakyQueuePlugin implements PixivFeaturePlugin {
        private final String id;
        private int idCalls;

        private FlakyQueuePlugin(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            if (++idCalls > 1) {
                throw new AssertionError("plugin id getter must not be called again");
            }
            return id;
        }

        @Override public String displayName() { return "plugin.label"; }
        @Override public String description() { return "plugin.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }

        @Override
        public List<QueueTypeContribution> queueTypes() {
            return List.of(new QueueTypeContribution(
                    "flaky-owner", "flaky-type", "flaky", "label", 1, null));
        }

        int idCalls() {
            return idCalls;
        }
    }
}
