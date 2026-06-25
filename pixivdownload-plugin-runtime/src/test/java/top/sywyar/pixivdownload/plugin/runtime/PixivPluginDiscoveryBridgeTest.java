package top.sywyar.pixivdownload.plugin.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.DefaultPluginDescriptor;
import org.pf4j.Plugin;
import org.pf4j.PluginDependency;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PF4J 发现桥接边界测试：以 Mockito 伪造 PF4J {@link PluginManager} / {@link PluginWrapper} 枚举，验证桥接从外置
 * 插件主类（实现 {@link PixivPluginProvider}）提取 {@link PixivFeaturePlugin}、按插件 classloader 解析资源、对坏
 * 插件 / 非入口契约插件给出清晰诊断且隔离失败不影响其它插件，以及跨边界类型只来自 plugin-api / JDK。
 */
@DisplayName("PixivPluginDiscoveryBridge 外置插件发现与诊断")
class PixivPluginDiscoveryBridgeTest {

    private final PixivPluginDiscoveryBridge bridge = new PixivPluginDiscoveryBridge();

    @Test
    @DisplayName("从已启动外置插件发现 PixivFeaturePlugin：携带来源 id 与插件 classloader")
    void discoversFeaturePluginFromStartedExternalPlugin() {
        ClassLoader pluginClassLoader = getClass().getClassLoader();
        PixivFeaturePlugin contributed = new TestFeaturePlugin("ext-stats");
        PluginManager manager = managerWith(
                startedWrapper("ext-stats-pack", new GoodProviderPlugin(List.of(contributed)), pluginClassLoader));

        PluginDiscoveryResult result = bridge.discover(manager);

        assertThat(result.failures()).isEmpty();
        assertThat(result.discovered()).hasSize(1);
        DiscoveredFeaturePlugin discovered = result.discovered().get(0);
        assertThat(discovered.sourcePluginId()).isEqualTo("ext-stats-pack");
        assertThat(discovered.featurePluginId()).isEqualTo("ext-stats");
        assertThat(discovered.plugin()).isSameAs(contributed);
        assertThat(discovered.classLoader()).isSameAs(pluginClassLoader);
        // 跨边界类型是 plugin-api 契约类型（父 loader 共享），不是 PF4J 实现类型
        assertThat(discovered.plugin()).isInstanceOf(PixivFeaturePlugin.class);
    }

    @Test
    @DisplayName("一个外置插件可贡献多个 PixivFeaturePlugin，全部被发现")
    void discoversMultipleFeaturePluginsFromOneProvider() {
        PluginManager manager = managerWith(startedWrapper("ext-media-pack",
                new GoodProviderPlugin(List.of(new TestFeaturePlugin("ext-stats"), new TestFeaturePlugin("ext-dup"))),
                getClass().getClassLoader()));

        PluginDiscoveryResult result = bridge.discover(manager);

        assertThat(result.failures()).isEmpty();
        assertThat(result.discovered()).extracting(DiscoveredFeaturePlugin::featurePluginId)
                .containsExactly("ext-stats", "ext-dup");
    }

    @Test
    @DisplayName("外置资源通过插件 classloader 可读，且核心壳应用 classloader 读不到（不误读外置资源）")
    void resolvesResourcesThroughPluginClassLoaderNotAppClassLoader(@TempDir Path pluginRoot) throws Exception {
        String resourceName = "pixiv-ext-only-marker.txt";
        Files.writeString(pluginRoot.resolve(resourceName), "external-only", StandardCharsets.UTF_8);
        // 仅插件 classloader 能解析该资源（parent=null：不经核心壳应用 loader 委派）
        try (URLClassLoader pluginClassLoader =
                     new URLClassLoader(new URL[]{pluginRoot.toUri().toURL()}, null)) {
            PluginManager manager = managerWith(startedWrapper("ext-resource-pack",
                    new GoodProviderPlugin(List.of(new TestFeaturePlugin("ext-resource"))), pluginClassLoader));

            PluginDiscoveryResult result = bridge.discover(manager);

            assertThat(result.discovered()).hasSize(1);
            ClassLoader discoveredCl = result.discovered().get(0).classLoader();
            assertThat(discoveredCl).isSameAs(pluginClassLoader);
            // 经插件 classloader 可读外置资源
            assertThat(discoveredCl.getResource(resourceName)).isNotNull();
            // 核心壳（app）classloader 读不到该外置资源——证明发现不依赖 app classloader
            assertThat(getClass().getClassLoader().getResource(resourceName)).isNull();
        }
    }

    @Test
    @DisplayName("主类未实现入口契约 PixivPluginProvider：清晰诊断、不致命、不影响同批其它插件发现")
    void nonProviderPluginYieldsClearDiagnosticWithoutAffectingOthers() {
        PixivFeaturePlugin good = new TestFeaturePlugin("ext-good");
        PluginManager manager = managerWith(
                startedWrapper("ext-broken-pack", new NotAProviderPlugin(), getClass().getClassLoader()),
                startedWrapper("ext-good-pack", new GoodProviderPlugin(List.of(good)), getClass().getClassLoader()));

        PluginDiscoveryResult result = bridge.discover(manager);

        // 坏插件不致命，好插件照常发现
        assertThat(result.discovered()).extracting(DiscoveredFeaturePlugin::featurePluginId)
                .containsExactly("ext-good");
        assertThat(result.failures()).hasSize(1);
        PluginLoadFailure failure = result.failures().get(0);
        assertThat(failure.source()).isEqualTo("ext-broken-pack");
        assertThat(failure.reason()).contains("PixivPluginProvider");
    }

    @Test
    @DisplayName("入口方法 featurePlugins() 抛错：被隔离捕获成诊断条目，不致命")
    void throwingProviderIsCapturedNotFatal() {
        PixivFeaturePlugin good = new TestFeaturePlugin("ext-good");
        PluginManager manager = managerWith(
                startedWrapper("ext-throwing-pack", new ThrowingProviderPlugin(), getClass().getClassLoader()),
                startedWrapper("ext-good-pack", new GoodProviderPlugin(List.of(good)), getClass().getClassLoader()));

        PluginDiscoveryResult result = bridge.discover(manager);

        assertThat(result.discovered()).extracting(DiscoveredFeaturePlugin::featurePluginId)
                .containsExactly("ext-good");
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).source()).isEqualTo("ext-throwing-pack");
        assertThat(result.failures().get(0).reason()).contains("featurePlugins()");
    }

    @Test
    @DisplayName("未启动的插件被跳过：既不发现也不计为失败")
    void notStartedPluginsAreSkipped() {
        PluginWrapper created = mock(PluginWrapper.class);
        when(created.getPluginState()).thenReturn(PluginState.CREATED);
        PluginManager manager = managerWith(created);

        PluginDiscoveryResult result = bridge.discover(manager);

        assertThat(result.discovered()).isEmpty();
        assertThat(result.failures()).isEmpty();
    }

    @Test
    @DisplayName("PluginManager 为 null：返回空结果")
    void nullManagerYieldsEmpty() {
        PluginDiscoveryResult result = bridge.discover(null);
        assertThat(result.discovered()).isEmpty();
        assertThat(result.failures()).isEmpty();
    }

    @Test
    @DisplayName("inspect 把 PF4J 描述符 + 功能插件元数据映射为统一描述符（id/displayName/kind 取功能插件，version/requires/deps/class 取插件包）")
    void inspectMapsPf4jDescriptorToUnifiedDescriptor() {
        DefaultPluginDescriptor pf4jDescriptor = new DefaultPluginDescriptor(
                "ext-stats-pack", "Stats Pack", "com.example.ExtStatsPlugin", "2.1.0",
                PluginApiVersion.MAJOR + "." + PluginApiVersion.MINOR, "Acme", "MIT");
        pf4jDescriptor.addDependency(new PluginDependency("novel@1.0"));
        PixivFeaturePlugin contributed = new TestFeaturePlugin("ext-stats");
        PluginManager manager = managerWith(startedWrapperWith(
                "ext-stats-pack", pf4jDescriptor, new GoodProviderPlugin(List.of(contributed)),
                getClass().getClassLoader()));

        PluginInventory inventory = bridge.inspect(manager);

        assertThat(inventory.failures()).isEmpty();
        assertThat(inventory.installations()).hasSize(1);
        PluginInstallation installation = inventory.installations().get(0);
        assertThat(installation.status()).isEqualTo(
                top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus.STARTED);
        assertThat(installation.registrable()).isTrue();
        assertThat(installation.plugin()).isSameAs(contributed);

        var descriptor = installation.descriptor();
        assertThat(descriptor.id()).isEqualTo("ext-stats");
        assertThat(descriptor.sourcePluginId()).isEqualTo("ext-stats-pack");
        assertThat(descriptor.version()).isEqualTo("2.1.0");
        assertThat(descriptor.pluginClass()).isEqualTo("com.example.ExtStatsPlugin");
        assertThat(descriptor.displayName()).isEqualTo("ext-stats.label");
        assertThat(descriptor.description()).isEqualTo("ext-stats.summary");
        // 功能插件未覆写 iconKey/colorToken：经桥接映射出 plugin-api 默认受控 token，不丢字段。
        assertThat(descriptor.iconKey()).isEqualTo("puzzle");
        assertThat(descriptor.colorToken()).isEqualTo("neutral");
        assertThat(descriptor.kind()).isEqualTo(PluginKind.FEATURE);
        assertThat(descriptor.isApiCompatible()).isTrue();
        assertThat(descriptor.dependencies()).singleElement()
                .satisfies(dependency -> {
                    assertThat(dependency.pluginId()).isEqualTo("novel");
                    assertThat(dependency.optional()).isFalse();
                });
    }

    @Test
    @DisplayName("核心 API 不兼容的插件包：标记 INCOMPATIBLE、不提取功能插件（拒绝接入），discover 并入失败")
    void inspectRejectsApiIncompatiblePackage() {
        DefaultPluginDescriptor pf4jDescriptor = new DefaultPluginDescriptor(
                "ext-future-pack", "Future", "com.example.FuturePlugin",
                "1.0.0", (PluginApiVersion.MAJOR + 1) + ".0", "Acme", "MIT");
        PluginManager manager = managerWith(startedWrapperWith(
                "ext-future-pack", pf4jDescriptor,
                new GoodProviderPlugin(List.of(new TestFeaturePlugin("ext-future"))), getClass().getClassLoader()));

        PluginInventory inventory = bridge.inspect(manager);

        assertThat(inventory.installations()).singleElement().satisfies(installation -> {
            assertThat(installation.status()).isEqualTo(
                    top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus.INCOMPATIBLE);
            assertThat(installation.registrable()).isFalse();
            assertThat(installation.plugin()).isNull();
            assertThat(installation.id()).isEqualTo("ext-future-pack");
        });

        // 投影为发现结果：不进入 discovered，并以兼容性诊断并入 failures（拒绝接入）
        PluginDiscoveryResult result = inventory.toDiscoveryResult();
        assertThat(result.discovered()).isEmpty();
        assertThat(result.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.reason()).contains("incompatible"));
    }

    @Test
    @DisplayName("discover 拒绝不兼容包但保留兼容包：混合批次只接入兼容者")
    void discoverRejectsIncompatibleKeepsCompatible() {
        DefaultPluginDescriptor incompatible = new DefaultPluginDescriptor(
                "ext-future-pack", "Future", "com.example.FuturePlugin",
                "1.0.0", (PluginApiVersion.MAJOR + 1) + ".0", "Acme", "MIT");
        DefaultPluginDescriptor compatible = new DefaultPluginDescriptor(
                "ext-ok-pack", "Ok", "com.example.OkPlugin", "1.0.0",
                PluginApiVersion.MAJOR + "." + PluginApiVersion.MINOR, "Acme", "MIT");
        PluginManager manager = managerWith(
                startedWrapperWith("ext-future-pack", incompatible,
                        new GoodProviderPlugin(List.of(new TestFeaturePlugin("ext-future"))),
                        getClass().getClassLoader()),
                startedWrapperWith("ext-ok-pack", compatible,
                        new GoodProviderPlugin(List.of(new TestFeaturePlugin("ext-ok"))),
                        getClass().getClassLoader()));

        PluginDiscoveryResult result = bridge.discover(manager);

        assertThat(result.discovered()).extracting(DiscoveredFeaturePlugin::featurePluginId)
                .containsExactly("ext-ok");
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).reason()).contains("incompatible");
    }

    @Test
    @DisplayName("inspectContextModules：已启动 + 兼容 + 声明配置类的插件包产出子 context 装配定义（来源 id / classloader / 配置类）")
    void inspectContextModulesExposesConfigurationClasses() {
        ClassLoader pluginClassLoader = getClass().getClassLoader();
        PluginManager manager = managerWith(startedWrapper("ext-cfg-pack",
                new ConfigProviderPlugin(List.of(SamplePluginConfig.class)), pluginClassLoader));

        List<PluginContextModule> modules = bridge.inspectContextModules(manager);

        assertThat(modules).singleElement().satisfies(module -> {
            assertThat(module.sourcePluginId()).isEqualTo("ext-cfg-pack");
            assertThat(module.classLoader()).isSameAs(pluginClassLoader);
            assertThat(module.configurationClasses()).containsExactly(SamplePluginConfig.class);
            assertThat(module.hasConfigurationClasses()).isTrue();
        });
    }

    @Test
    @DisplayName("inspectContextModules：未声明配置类的插件包不产出子 context 装配定义")
    void inspectContextModulesSkipsPackageWithoutConfigurationClasses() {
        PluginManager manager = managerWith(
                startedWrapper("ext-cfg-pack", new ConfigProviderPlugin(List.of()), getClass().getClassLoader()),
                startedWrapper("ext-plain-pack",
                        new GoodProviderPlugin(List.of(new TestFeaturePlugin("ext-plain"))),
                        getClass().getClassLoader()));

        assertThat(bridge.inspectContextModules(manager)).isEmpty();
    }

    @Test
    @DisplayName("inspectContextModules：核心 API 不兼容的插件包不参与子 context 装配（同 inspect 的接入兼容门）")
    void inspectContextModulesRejectsIncompatiblePackage() {
        DefaultPluginDescriptor incompatible = new DefaultPluginDescriptor(
                "ext-future-pack", "Future", "com.example.FuturePlugin",
                "1.0.0", (PluginApiVersion.MAJOR + 1) + ".0", "Acme", "MIT");
        PluginManager manager = managerWith(startedWrapperWith("ext-future-pack", incompatible,
                new ConfigProviderPlugin(List.of(SamplePluginConfig.class)), getClass().getClassLoader()));

        assertThat(bridge.inspectContextModules(manager)).isEmpty();
    }

    @Test
    @DisplayName("inspectContextModules：主类未实现入口契约的插件包被跳过，不致命")
    void inspectContextModulesSkipsNonProvider() {
        PluginManager manager = managerWith(
                startedWrapper("ext-broken-pack", new NotAProviderPlugin(), getClass().getClassLoader()));

        assertThat(bridge.inspectContextModules(manager)).isEmpty();
    }

    @Test
    @DisplayName("inspectContextModules：PluginManager 为 null 返回空")
    void inspectContextModulesNullManagerYieldsEmpty() {
        assertThat(bridge.inspectContextModules(null)).isEmpty();
    }

    // ---- 测试夹具 ----

    private static PluginManager managerWith(PluginWrapper... wrappers) {
        PluginManager manager = mock(PluginManager.class);
        when(manager.getPlugins()).thenReturn(List.of(wrappers));
        return manager;
    }

    private static PluginWrapper startedWrapper(String pluginId, Plugin plugin, ClassLoader classLoader) {
        PluginWrapper wrapper = mock(PluginWrapper.class);
        when(wrapper.getPluginId()).thenReturn(pluginId);
        when(wrapper.getPluginState()).thenReturn(PluginState.STARTED);
        when(wrapper.getPlugin()).thenReturn(plugin);
        when(wrapper.getPluginClassLoader()).thenReturn(classLoader);
        return wrapper;
    }

    private static PluginWrapper startedWrapperWith(String pluginId, PluginDescriptor descriptor,
                                                    Plugin plugin, ClassLoader classLoader) {
        PluginWrapper wrapper = startedWrapper(pluginId, plugin, classLoader);
        when(wrapper.getDescriptor()).thenReturn(descriptor);
        return wrapper;
    }

    /** 外置插件主类：同时是 PF4J Plugin 与入口契约 PixivPluginProvider（运行期由插件 classloader 创建）。 */
    private static final class GoodProviderPlugin extends Plugin implements PixivPluginProvider {
        private final List<PixivFeaturePlugin> featurePlugins;

        GoodProviderPlugin(List<PixivFeaturePlugin> featurePlugins) {
            this.featurePlugins = featurePlugins;
        }

        @Override
        public List<PixivFeaturePlugin> featurePlugins() {
            return featurePlugins;
        }
    }

    /** 主类未实现入口契约（典型坏插件 / 误把 plugin-api 打进插件包导致同名类不同 loader 的情形）。 */
    private static final class NotAProviderPlugin extends Plugin {
    }

    /** 入口契约声明了 Spring 配置类（用于子 context 装配定义清点）。 */
    private static final class ConfigProviderPlugin extends Plugin implements PixivPluginProvider {
        private final List<Class<?>> configurationClasses;

        ConfigProviderPlugin(List<Class<?>> configurationClasses) {
            this.configurationClasses = configurationClasses;
        }

        @Override
        public List<PixivFeaturePlugin> featurePlugins() {
            return List.of(new TestFeaturePlugin("ext-cfg"));
        }

        @Override
        public List<Class<?>> configurationClasses() {
            return configurationClasses;
        }
    }

    /** 占位配置类令牌（清点只收集 Class 令牌，不实例化，故无需真实 @Configuration）。 */
    private static final class SamplePluginConfig {
    }

    /** 入口方法抛错的插件。 */
    private static final class ThrowingProviderPlugin extends Plugin implements PixivPluginProvider {
        @Override
        public List<PixivFeaturePlugin> featurePlugins() {
            throw new IllegalStateException("boom");
        }
    }

    /** 最小功能插件实现（只用 plugin-api 契约类型，证明跨边界类型自包含）。 */
    private static final class TestFeaturePlugin implements PixivFeaturePlugin {
        private final String id;

        TestFeaturePlugin(String id) {
            this.id = id;
        }

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
    }
}
