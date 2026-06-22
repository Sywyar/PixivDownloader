package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.runtime.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.PluginDiscoveryResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StaticResourceRegistry 静态资源注册中心")
class StaticResourceRegistryTest {

    private static final ClassLoader CL = StaticResourceRegistryTest.class.getClassLoader();

    private static StaticResourceRegistry emptyRegistry() {
        return new StaticResourceRegistry(new PluginRegistry(List.of()));
    }

    private static StaticResourceContribution res(String pluginId, String name) {
        return new StaticResourceContribution(
                pluginId, "classpath:/static/" + name + "/", "/" + name + "/");
    }

    @Test
    @DisplayName("构造时从 PluginRegistry 收集全部内置插件静态资源（核心公共库 + 下载工作台 + 三 web 功能插件页面目录；stats 已外置）")
    void collectsFromBuiltInPlugins() {
        StaticResourceRegistry registry =
                new StaticResourceRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        assertThat(registry.resources())
                .extracting(registered -> registered.contribution().publicPathPrefix())
                .containsExactlyInAnyOrder(
                        "/js/", "/css/", "/vendor/",
                        "/pixiv-batch/",
                        "/pixiv-gallery/", "/pixiv-artwork/", "/pixiv-showcase/", "/pixiv-series/",
                        "/pixiv-novel-gallery/", "/pixiv-novel/", "/pixiv-novel-download/",
                        "/pixiv-duplicates/");
        assertThat(registry.resources())
                .filteredOn(registered -> registered.contribution().publicPathPrefix().equals("/js/"))
                .singleElement()
                .satisfies(registered -> {
                    assertThat(registered.pluginId()).isEqualTo("core");
                    assertThat(registered.classLoader()).isNotNull();
                });
    }

    @Test
    @DisplayName("全部内置静态资源 classpathLocation 均解析到存在的目录（防路径声明笔误 / 解析失败）")
    void builtInClasspathLocationsResolveToExistingDirectories() {
        StaticResourceRegistry registry =
                new StaticResourceRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        assertThat(registry.resources()).isNotEmpty();
        for (StaticResourceRegistry.RegisteredStaticResource registered : registry.resources()) {
            Resource location = new DefaultResourceLoader(registered.classLoader())
                    .getResource(registered.contribution().classpathLocation());
            assertThat(location.exists())
                    .as("静态资源目录 %s 应存在", registered.contribution().classpathLocation())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("register → unregister → 再 register 后快照与首次注册一致（可逆性）")
    void registerUnregisterRoundTrip() {
        StaticResourceRegistry registry = emptyRegistry();
        List<StaticResourceContribution> items = List.of(res("demo", "a"), res("demo", "b"));
        registry.register("demo", CL, items);
        List<StaticResourceRegistry.RegisteredStaticResource> first = registry.resources();
        registry.unregister("demo");
        assertThat(registry.resources()).isEmpty();
        registry.register("demo", CL, items);
        assertThat(registry.resources()).isEqualTo(first);
    }

    @Test
    @DisplayName("unregister 对未注册过静态资源的 pluginId 静默返回（统一卸载流程对每个插件都会调用）")
    void unregisterUnknownPluginIsSilent() {
        StaticResourceRegistry registry = emptyRegistry();
        registry.unregister("never-registered");
        assertThat(registry.resources()).isEmpty();
    }

    @Test
    @DisplayName("同一 pluginId 重复注册立即抛出")
    void duplicatePluginRegistrationRejected() {
        StaticResourceRegistry registry = emptyRegistry();
        registry.register("demo", CL, List.of(res("demo", "a")));
        assertThatThrownBy(() -> registry.register("demo", CL, List.of(res("demo", "b"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("对外路径前缀全局冲突立即抛出（跨插件指向同一前缀）")
    void duplicatePrefixAcrossPluginsRejected() {
        StaticResourceRegistry registry = emptyRegistry();
        registry.register("a", CL, List.of(res("a", "shared")));
        assertThatThrownBy(() -> registry.register("b", CL, List.of(res("b", "shared"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/shared/")
                .hasMessageContaining("b");
    }

    @Test
    @DisplayName("同一插件内对外路径前缀重复也立即抛出")
    void duplicatePrefixWithinPluginRejected() {
        StaticResourceRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register("demo", CL, List.of(res("demo", "dup"), res("demo", "dup"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/dup/");
    }

    @Test
    @DisplayName("非法输入拒绝：pluginId / classLoader / 列表 / classpath 位置 / 前缀 / pluginId 一致性")
    void invalidInputRejected() {
        StaticResourceRegistry registry = emptyRegistry();
        // pluginId 空
        assertThatThrownBy(() -> registry.register(" ", CL, List.of(res(" ", "a"))))
                .isInstanceOf(IllegalStateException.class);
        // classLoader 为 null
        assertThatThrownBy(() -> registry.register("demo", null, List.of(res("demo", "a"))))
                .isInstanceOf(IllegalStateException.class);
        // 列表为空
        assertThatThrownBy(() -> registry.register("demo", CL, List.of()))
                .isInstanceOf(IllegalStateException.class);
        // classpathLocation 不以 classpath: 开头
        assertThatThrownBy(() -> registry.register("demo", CL, List.of(
                new StaticResourceContribution("demo", "/static/a/", "/a/"))))
                .isInstanceOf(IllegalStateException.class);
        // classpathLocation 不以 / 结尾
        assertThatThrownBy(() -> registry.register("demo", CL, List.of(
                new StaticResourceContribution("demo", "classpath:/static/a", "/a/"))))
                .isInstanceOf(IllegalStateException.class);
        // publicPathPrefix 不以 / 开头
        assertThatThrownBy(() -> registry.register("demo", CL, List.of(
                new StaticResourceContribution("demo", "classpath:/static/a/", "a/"))))
                .isInstanceOf(IllegalStateException.class);
        // publicPathPrefix 不以 / 结尾
        assertThatThrownBy(() -> registry.register("demo", CL, List.of(
                new StaticResourceContribution("demo", "classpath:/static/a/", "/a"))))
                .isInstanceOf(IllegalStateException.class);
        // 声明的 pluginId 与注册 pluginId 不一致
        assertThatThrownBy(() -> registry.register("demo", CL, List.of(res("other", "a"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mismatch");
    }

    @Test
    @DisplayName("resources() 返回不可变快照，外部不可修改")
    void snapshotIsImmutable() {
        StaticResourceRegistry registry = emptyRegistry();
        registry.register("demo", CL, List.of(res("demo", "a")));
        List<StaticResourceRegistry.RegisteredStaticResource> resources = registry.resources();
        assertThatThrownBy(() -> resources.add(
                new StaticResourceRegistry.RegisteredStaticResource("x", res("x", "x"), CL)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("外置插件静态资源用桥接提供的 classloader 注册，而非插件对象 class 的 loader")
    void externalStaticResourceUsesBridgeClassLoaderNotPluginClassLoader() {
        // 桥接捕获的插件 classloader（真实场景为 PF4J 插件 classloader），与插件实例 class 的 loader 不同
        ClassLoader bridgeClassLoader = new ClassLoader(StaticResourceRegistryTest.class.getClassLoader()) {};
        ExternalStaticPlugin external = new ExternalStaticPlugin();
        // 前置：本测试只有在「插件对象 class 的 loader != 桥接 classloader」时才有意义
        assertThat(external.getClass().getClassLoader()).isNotSameAs(bridgeClassLoader);

        PluginRegistry registry = new PluginRegistry(
                List.of(new CorePlaceholderPlugin()), new PluginToggleProperties(),
                new PluginDiscoveryResult(
                        List.of(new DiscoveredFeaturePlugin("ext-static-pack", external, bridgeClassLoader)),
                        List.of()));
        StaticResourceRegistry registry2 = new StaticResourceRegistry(registry);

        StaticResourceRegistry.RegisteredStaticResource registered = registry2.resources().stream()
                .filter(r -> r.pluginId().equals("ext-static")).findFirst().orElseThrow();
        assertThat(registered.classLoader())
                .as("应使用 RegisteredPlugin.classLoader()（桥接 classloader），而非 plugin.getClass().getClassLoader()")
                .isSameAs(bridgeClassLoader)
                .isNotSameAs(external.getClass().getClassLoader());
    }

    /** 最小核心插件占位（无静态资源），仅作对照。 */
    private static final class CorePlaceholderPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "core"; }
        @Override public String displayName() { return "core.label"; }
        @Override public String description() { return "core.summary"; }
        @Override public PluginKind kind() { return PluginKind.CORE; }
    }

    /** 外置功能插件：声明一条静态资源，用于验证注册中心采用桥接 classloader。 */
    private static final class ExternalStaticPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "ext-static"; }
        @Override public String displayName() { return "ext-static.label"; }
        @Override public String description() { return "ext-static.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution("ext-static", "classpath:/ext-static/", "/ext-static/"));
        }
    }
}
