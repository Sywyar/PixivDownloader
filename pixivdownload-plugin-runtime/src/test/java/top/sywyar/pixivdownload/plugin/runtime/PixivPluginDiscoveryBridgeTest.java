package top.sywyar.pixivdownload.plugin.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.Plugin;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

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
