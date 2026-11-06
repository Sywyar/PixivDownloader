package top.sywyar.pixivdownload.plugin.runtime.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pf4j.Plugin;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.*;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.sdk.SdkVersion;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("已验证描述符驱动的插件发现与错误隔离")
class PixivPluginDiscoveryBridgeTest {
    private final PixivPluginDiscoveryBridge bridge = new PixivPluginDiscoveryBridge();
    private final PluginManager manager = mock(PluginManager.class);
    private final PluginWrapper wrapper = mock(PluginWrapper.class);
    private final PixivFeaturePlugin feature = mock(PixivFeaturePlugin.class);

    private void loaded(Plugin plugin, ClassLoader loader) {
        when(manager.getPlugin("fixture")).thenReturn(wrapper);
        when(wrapper.getPluginId()).thenReturn("fixture");
        when(wrapper.getPluginState()).thenReturn(PluginState.RESOLVED);
        when(wrapper.getPlugin()).thenReturn(plugin);
        when(wrapper.getPluginClassLoader()).thenReturn(loader);
    }

    @Test @DisplayName("身份和展示信息只取已验证描述符，不调用插件元数据")
    void metadataComesFromVerifiedDescriptor() {
        loaded(new Provider(() -> feature), getClass().getClassLoader());
        var descriptor = descriptor(List.of());
        var inventory = bridge.inspectLoadedPackage(manager, descriptor);
        assertThat(inventory.failures()).isEmpty();
        assertThat(inventory.installations()).singleElement().satisfies(value -> {
            assertThat(value.descriptor()).isSameAs(descriptor);
            assertThat(value.id()).isEqualTo("fixture");
            assertThat(value.plugin()).isSameAs(feature);
        });
        verify(feature, never()).id();
        verify(feature, never()).displayName();
        verify(feature, never()).description();
        verify(feature, never()).kind();
    }

    @Test @DisplayName("资源沿插件加载器解析，宿主不可见的资源仍可读取")
    void preservesPluginResourceLoader(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("external-only.txt"), "fixture", StandardCharsets.UTF_8);
        try (var loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, null)) {
            loaded(new Provider(() -> feature), loader);
            var result = bridge.inspectLoadedPackage(manager, descriptor(List.of())).toDiscoveryResult();
            assertThat(result.failures()).isEmpty();
            assertThat(result.discovered()).singleElement().satisfies(value -> {
                assertThat(value.classLoader()).isSameAs(loader);
                assertThat(value.classLoader().getResource("external-only.txt")).isNotNull();
            });
            assertThat(getClass().getClassLoader().getResource("external-only.txt")).isNull();
        }
    }

    @Test @DisplayName("不同 SDK 主版本在获取插件实例前被拒绝")
    void incompatibleSdkDoesNotInstantiatePlugin() {
        loaded(new Provider(() -> { throw new AssertionError("must not execute"); }), getClass().getClassLoader());
        clearInvocations(wrapper);
        var inventory = bridge.inspectLoadedPackage(manager, descriptor(List.of(), (SdkVersion.MAJOR - 1) + ".0"));
        assertThat(inventory.installations()).singleElement().satisfies(value -> {
            assertThat(value.status()).isEqualTo(PluginStatus.INCOMPATIBLE);
            assertThat(value.plugin()).isNull();
        });
        assertThat(inventory.contextModules()).isEmpty();
        verify(wrapper, never()).getPlugin();
    }

    @Test @DisplayName("包身份不符时不执行插件代码")
    void rejectsPackageIdentityMismatch() {
        loaded(new Provider(() -> feature), getClass().getClassLoader());
        when(wrapper.getPluginId()).thenReturn("another-package");
        clearInvocations(wrapper);
        var result = bridge.inspectLoadedPackage(manager, descriptor(List.of()));
        assertThat(result.installations()).isEmpty();
        assertThat(result.failures()).singleElement().satisfies(value -> assertThat(value.reason()).contains("package id"));
        verify(wrapper, never()).getPlugin();
    }

    @Test @DisplayName("非入口类型和空功能对象被隔离")
    void rejectsInvalidEntrypoints() {
        for (Plugin plugin : List.of(new Plugin(), new Provider(() -> null))) {
            loaded(plugin, getClass().getClassLoader());
            var result = bridge.inspectLoadedPackage(manager, descriptor(List.of()));
            assertThat(result.installations()).isEmpty();
            assertThat(result.failures()).hasSize(1);
        }
    }

    @Test @DisplayName("普通初始化错误隔离且不妨碍下一包发现")
    void isolatesNonFatalFailures() {
        loaded(new Provider(() -> { throw new AssertionError("fixture failure"); }), getClass().getClassLoader());
        var failed = bridge.inspectLoadedPackage(manager, descriptor(List.of()));
        assertThat(failed.installations()).isEmpty();
        assertThat(failed.failures()).singleElement().satisfies(value -> assertThat(value.reason()).contains("fixture failure"));
        loaded(new Provider(() -> feature), getClass().getClassLoader());
        assertThat(bridge.inspectLoadedPackage(manager, descriptor(List.of())).installations()).hasSize(1);
    }

    @Test @DisplayName("虚拟机致命错误原样传播")
    void propagatesFatalFailure() {
        var fatal = new OutOfMemoryError("fixture");
        loaded(new Provider(() -> { throw fatal; }), getClass().getClassLoader());
        assertThatThrownBy(() -> bridge.inspectLoadedPackage(manager, descriptor(List.of()))).isSameAs(fatal);
    }

    @ParameterizedTest @EnumSource(value = PluginState.class, names = {"FAILED", "UNLOADED"})
    @DisplayName("失败或卸载的包不参与发现")
    void ignoresUnavailablePackages(PluginState state) {
        loaded(new Provider(() -> feature), getClass().getClassLoader());
        when(wrapper.getPluginState()).thenReturn(state);
        clearInvocations(wrapper);
        assertThat(bridge.inspectLoadedPackage(manager, descriptor(List.of())).installations()).isEmpty();
        verify(wrapper, never()).getPlugin();
    }

    @Test @DisplayName("缺失管理器、描述符或包时返回空清单")
    void handlesAbsentPackage() {
        assertThat(bridge.inspectLoadedPackage(null, descriptor(List.of())).installations()).isEmpty();
        assertThat(bridge.inspectLoadedPackage(manager, null).installations()).isEmpty();
        assertThat(bridge.inspectLoadedPackage(manager, descriptor(List.of())).installations()).isEmpty();
    }

    @Test @DisplayName("配置类由描述符指定并保持插件加载器所有权")
    void loadsDeclaredConfiguration() throws Exception {
        loaded(new Provider(() -> feature), getClass().getClassLoader());
        var descriptor = descriptor(List.of(Configuration.class.getName()));
        var inventory = bridge.inspectLoadedPackage(manager, descriptor);
        assertThat(inventory.failures()).isEmpty();
        assertThat(inventory.contextModules()).singleElement().satisfies(module ->
                assertThat(module.configurationClasses()).containsExactly(Configuration.class));
        try (var foreign = new URLClassLoader(new URL[0], getClass().getClassLoader())) {
            loaded(new Provider(() -> feature), foreign);
            var rejected = bridge.inspectLoadedPackage(manager, descriptor);
            assertThat(rejected.installations()).isEmpty();
            assertThat(rejected.failures()).singleElement().satisfies(value ->
                    assertThat(value.reason()).contains("owned by the plugin classloader"));
        }
    }

    @Test @DisplayName("缺失配置类与无效存储声明拒绝注册")
    void rejectsInvalidDeclarations() {
        loaded(new Provider(() -> feature), getClass().getClassLoader());
        assertThat(bridge.inspectLoadedPackage(manager, descriptor(List.of("fixture.Absent"))).failures()).hasSize(1);
        when(feature.privateSchema()).thenThrow(new IllegalArgumentException("invalid schema"));
        var rejected = bridge.inspectLoadedPackage(manager, descriptor(List.of()));
        assertThat(rejected.installations()).isEmpty();
        assertThat(rejected.failures()).singleElement().satisfies(value -> assertThat(value.reason()).contains("storage declaration"));
    }

    @Test @DisplayName("仅声明私有结构也创建上下文模块，空插件不创建")
    void storageDeclarationCreatesContext() {
        loaded(new Provider(() -> feature), getClass().getClassLoader());
        assertThat(bridge.inspectLoadedPackage(manager, descriptor(List.of())).contextModules()).isEmpty();
        var schema = new SchemaContribution(List.of(new TableSpec("entries",
                List.of(new ColumnSpec("id", "INTEGER", false, null, 1)), List.of())), List.of(), List.of());
        when(feature.privateSchema()).thenReturn(List.of(schema));
        assertThat(bridge.inspectLoadedPackage(manager, descriptor(List.of())).contextModules())
                .singleElement().satisfies(module -> assertThat(module.privateSchema()).containsExactly(schema));
    }

    private static PluginDescriptor descriptor(List<String> classes) {
        return descriptor(classes, SdkVersion.MAJOR + "." + SdkVersion.MINOR);
    }

    private static PluginDescriptor descriptor(List<String> classes, String requires) {
        return new PluginDescriptor("fixture", "fixture", "1.0.0", VersionRequirement.parseSdk(requires),
                List.of(), "com.example.Plugin", "fixture", "plugin.name", "plugin.summary", "puzzle", "neutral",
                PluginKind.FEATURE, List.of(), PluginLifecyclePolicy.PROCESS_RESTART,
                PluginExecutionMode.HOST_PROCESS_FULL_TRUST, classes, PluginRiskDeclaration.absent());
    }

    private static final class Provider extends Plugin implements PixivPluginProvider {
        private final Supplier<PixivFeaturePlugin> feature;
        Provider(Supplier<PixivFeaturePlugin> feature) { this.feature = feature; }
        @Override public PixivFeaturePlugin featurePlugin() { return feature.get(); }
    }

    private static final class Configuration {}
}
