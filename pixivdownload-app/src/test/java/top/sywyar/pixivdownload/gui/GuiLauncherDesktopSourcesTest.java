package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GuiLauncherDesktopSourcesTest {
    @Test
    @DisplayName("等价 YAML 层级共享 Spring 平铺规则，重复属性按文件顺序覆盖")
    void yamlToggleShapesFollowSpringFlattening(@TempDir Path temp) throws Exception {
        Path config = temp.resolve("config.yaml");
        for (boolean enabled : List.of(false, true)) {
            for (String yaml : List.of(
                    "plugins.fixture.enabled: " + enabled + "\n",
                    "plugins:\n  fixture:\n    enabled: " + enabled + "\n",
                    "plugins.fixture:\n  enabled: " + enabled + "\n",
                    "plugins:\n  fixture.enabled: " + enabled + "\n",
                    "plugins.fixture.enabled: " + !enabled + "\nplugins:\n  fixture:\n    enabled: " + enabled + "\n",
                    "plugins:\n  fixture:\n    enabled: " + !enabled + "\nplugins.fixture.enabled: " + enabled + "\n")) {
                Files.writeString(config, yaml, StandardCharsets.UTF_8);
                var snapshot = GuiLauncher.readPluginEnabledSnapshot(config);
                assertThat(snapshot.isEnabled("fixture")).as(yaml).isEqualTo(enabled);
                assertThat(snapshot.isEnabled("unconfigured")).isTrue();
                assertThat(snapshot.diagnostics()).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("管理端写入的扁平启停键在桌面重启后撤回贡献，重新启用后恢复")
    void flatPluginToggleControlsDesktopContributions(@TempDir Path temp) throws Exception {
        Path config = temp.resolve("config.yaml");
        Files.writeString(config, "99: true\nplugins.enabled: ignored\nplugins:\n  legacy:\n    enabled: false\n", StandardCharsets.UTF_8);
        var editor = new ConfigFileEditor(config);
        for (boolean enabled : List.of(false, true)) {
            editor.write("plugins.fixture.enabled", Boolean.toString(enabled));
            var snapshot = GuiLauncher.readPluginEnabledSnapshot(config);
            assertThat(snapshot.isEnabled("fixture")).isEqualTo(enabled);
            assertThat(snapshot.isEnabled("legacy")).isFalse();
            assertThat(snapshot.isEnabled("unconfigured")).isTrue();
            var toggles = new PluginToggleProperties();
            snapshot.disabledFeatureIds().forEach(id -> toggles.setEnabled(id, false));
            var plugin = new TestPlugin();
            var discovery = new PluginDiscoveryResult(List.of(new DiscoveredFeaturePlugin(
                    plugin.id(), plugin.id(), plugin, getClass().getClassLoader())), List.of());
            var registry = new PluginRegistry(List.of(), toggles, discovery);
            assertThat(GuiLauncher.buildDesktopUiPluginSnapshots(GuiLauncher.buildDesktopUiPluginSources(registry)))
                    .hasSize(enabled ? 1 : 0);
            assertThat(registry.allRegisteredPlugins()).hasSize(1);
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("桌面贡献初始化错误只排除故障插件，JVM 致命错误保持传播")
    void contributionErrorsAreIsolatedPerPlugin() {
        PixivFeaturePlugin broken = org.mockito.Mockito.mock(PixivFeaturePlugin.class);
        org.mockito.Mockito.when(broken.guiThemes()).thenThrow(new ExceptionInInitializerError("theme metadata"));
        var sources = List.of(
                new DesktopUiPluginSource("broken", false, broken, getClass().getClassLoader()),
                new DesktopUiPluginSource("fixture", false, new TestPlugin(), getClass().getClassLoader()));
        assertThat(GuiLauncher.buildDesktopUiPluginSnapshots(sources))
                .extracting(snapshot -> snapshot.id()).containsExactly("fixture");
        StackOverflowError fatal = new StackOverflowError("VM failure");
        org.mockito.Mockito.doThrow(fatal).when(broken).guiThemes();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> GuiLauncher.buildDesktopUiPluginSnapshots(sources))
                .isSameAs(fatal);
    }

    @Test
    void reusesSourcesUntilTheRuntimeDiscoveryChanges() {
        PluginDiscoveryResult initial = PluginDiscoveryResult.empty();
        PluginDiscoveryResult changed = new PluginDiscoveryResult(
                List.of(), List.of(new PluginLoadFailure("broken.jar", "invalid")));
        PluginDiscoveryResult[] current = {initial};
        AtomicInteger builds = new AtomicInteger();
        List<DesktopUiPluginSource> initialSources = List.of();
        var sources = GuiLauncher.memoizedDesktopUiSources(
                () -> current[0], discovery -> {
                    builds.incrementAndGet();
                    return List.of();
                }, initial, initialSources);

        assertThat(sources.get()).isSameAs(initialSources);
        assertThat(sources.get()).isSameAs(initialSources);
        assertThat(builds).hasValue(0);

        current[0] = changed;
        sources.get();
        sources.get();
        assertThat(builds).hasValue(1);
    }

    @Test
    void providerSnapshotRetainsOnlyMaterializedPluginValues() {
        PixivFeaturePlugin plugin = new TestPlugin();
        ClassLoader classLoader = new ClassLoader(getClass().getClassLoader()) { };

        var snapshot = GuiLauncher.buildDesktopUiPluginSnapshots(List.of(new DesktopUiPluginSource(
                plugin.id(), false, plugin, classLoader, "fixture-package", 7L))).get(0);

        assertThat(snapshot.id()).isEqualTo("fixture");
        assertThat(snapshot.packageId()).isEqualTo("fixture-package");
        assertThat(snapshot.generation()).isEqualTo(7L);
        assertThat(snapshot.getClass().getDeclaredFields())
                .extracting(field -> field.getType().getName())
                .noneMatch(name -> name.contains("PixivFeaturePlugin") || name.contains("ClassLoader"));
    }

    @Test
    void replacedSourceSnapshotReleasesPluginAndClassLoader() throws Exception {
        ReleaseProbe probe = replaceSourceSnapshot();

        for (int attempt = 0; attempt < 20 && !probe.collected(); attempt++) {
            System.gc();
            Thread.sleep(10L);
        }
        Assumptions.assumeTrue(probe.collected(),
                "GC did not run conclusively after the deterministic source replacement checks passed");
    }

    @Test
    void rebuildsMaterializedBundlesWhenTheRuntimeDiscoveryChanges() {
        PluginDiscoveryResult initial = PluginDiscoveryResult.empty();
        PluginDiscoveryResult changed = new PluginDiscoveryResult(
                List.of(), List.of(new PluginLoadFailure("replacement.jar", "changed")));
        PluginDiscoveryResult[] current = {initial};
        AtomicInteger builds = new AtomicInteger();
        WebI18nBundleRegistry initialBundles = bundles();
        var registry = GuiLauncher.memoizedDesktopUiBundles(
                () -> current[0], discovery -> {
                    builds.incrementAndGet();
                    return bundles();
                }, initial, initialBundles);

        assertThat(registry.get()).isSameAs(initialBundles);
        current[0] = changed;
        WebI18nBundleRegistry replacement = registry.get();
        assertThat(replacement).isNotSameAs(initialBundles);
        assertThat(registry.get()).isSameAs(replacement);
        assertThat(builds).hasValue(1);
    }

    private static ReleaseProbe replaceSourceSnapshot() {
        PluginDiscoveryResult initial = PluginDiscoveryResult.empty();
        PluginDiscoveryResult changed = new PluginDiscoveryResult(
                List.of(), List.of(new PluginLoadFailure("replacement.jar", "changed")));
        PluginDiscoveryResult[] current = {initial};
        PixivFeaturePlugin plugin = new TestPlugin();
        ClassLoader classLoader = new ClassLoader(GuiLauncherDesktopSourcesTest.class.getClassLoader()) { };
        DesktopUiPluginSource source = new DesktopUiPluginSource(
                plugin.id(), false, plugin, classLoader, plugin.id(), 1L);
        WeakReference<PixivFeaturePlugin> pluginReference = new WeakReference<>(plugin);
        WeakReference<ClassLoader> classLoaderReference = new WeakReference<>(classLoader);
        var sources = GuiLauncher.memoizedDesktopUiSources(
                () -> current[0], discovery -> List.of(new DesktopUiPluginSource(
                        "fixture", false, new TestPlugin(),
                        GuiLauncherDesktopSourcesTest.class.getClassLoader(), "fixture", 2L)),
                initial, List.of(source));

        current[0] = changed;
        assertThat(sources.get()).singleElement().satisfies(replacement -> {
            assertThat(replacement.generation()).isEqualTo(2L);
            assertThat(replacement).isNotSameAs(source);
        });
        return new ReleaseProbe(pluginReference, classLoaderReference);
    }

    private static WebI18nBundleRegistry bundles() {
        return new WebI18nBundleRegistry(new PluginRegistry(List.of()));
    }

    private record ReleaseProbe(WeakReference<PixivFeaturePlugin> plugin,
                                WeakReference<ClassLoader> classLoader) {
        boolean collected() {
            return plugin.get() == null && classLoader.get() == null;
        }
    }

    private static final class TestPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "fixture"; }
        @Override public String displayName() { return "fixture"; }
        @Override public String description() { return "fixture"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
    }
}
