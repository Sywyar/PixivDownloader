package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry.RegisteredPlugin;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;

@DisplayName("StaticResourceRegistry 静态资源注册中心")
class StaticResourceRegistryTest {

    private static StaticResourceRegistry emptyRegistry() {
        return new AutoRegisteringStaticResourceRegistry();
    }

    /** 既有资源校验用夹具：先把 owner 接入真实 PluginRegistry，再走生产 exact-identity 提交路径。 */
    private static final class AutoRegisteringStaticResourceRegistry extends StaticResourceRegistry {
        private final PluginRegistry plugins;

        private AutoRegisteringStaticResourceRegistry() {
            this(new PluginRegistry(List.of()));
        }

        private AutoRegisteringStaticResourceRegistry(PluginRegistry plugins) {
            super(plugins);
            this.plugins = plugins;
        }

        @Override
        public void register(RegisteredPlugin owner, List<StaticResourceContribution> resources) {
            PreparedResources prepared = prepare(owner, resources);
            if (!plugins.containsIdentity(owner)) {
                plugins.register(owner);
            }
            super.register(prepared);
        }
    }

    private static RegisteredPlugin owner(String pluginId) {
        PixivFeaturePlugin plugin = new RuntimeStaticPlugin(pluginId);
        return new RegisteredPlugin(plugin, PluginSource.BUILT_IN, plugin.getClass().getClassLoader());
    }

    private static StaticResourceContribution res(String name) {
        return new StaticResourceContribution(
                "classpath:/static/" + name + "/", "/" + name + "/");
    }

    @Test
    @DisplayName("构造时从 PluginRegistry 收集全部内置插件静态资源（核心公共库 + 插件市场；novel 等可选能力已外置）")
    void collectsFromBuiltInPlugins() {
        StaticResourceRegistry registry =
                new StaticResourceRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        assertThat(registry.resources())
                .extracting(registered -> registered.contribution().publicPathPrefix())
                .containsExactlyInAnyOrder(
                        "/js/", "/css/", "/vendor/",
                        "/plugin-market/");
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
            assertThat(registered.location().exists())
                    .as("静态资源目录 %s 应存在", registered.contribution().classpathLocation())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("register → unregister → 再 register 后快照与首次注册一致（可逆性）")
    void registerUnregisterRoundTrip() {
        StaticResourceRegistry registry = emptyRegistry();
        List<StaticResourceContribution> items = List.of(res("a"), res("b"));
        RegisteredPlugin owner = owner("demo");
        registry.register(owner, items);
        List<StaticResourceRegistry.RegisteredStaticResource> first = registry.resources();
        registry.unregister("demo");
        assertThat(registry.resources()).isEmpty();
        registry.register(owner, items);
        assertThat(registry.resources()).isEqualTo(first);
    }

    @Test
    @DisplayName("准备令牌不可重放且旧 owner 在同 id 新代接入后不能直接重发资源")
    void preparedTokenAndStaleOwnerCannotReplay() {
        PluginRegistry plugins = new PluginRegistry(List.of());
        StaticResourceRegistry registry = new StaticResourceRegistry(plugins);
        RegisteredPlugin oldOwner = owner("demo");
        plugins.register(oldOwner);
        StaticResourceRegistry.PreparedResources prepared =
                registry.prepare(oldOwner, List.of(res("a")));

        registry.register(prepared);
        registry.unregister("demo");
        assertThatThrownBy(() -> registry.register(prepared))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already attempted");

        plugins.unregister(oldOwner);
        RegisteredPlugin newOwner = owner("demo");
        plugins.register(newOwner);
        assertThatThrownBy(() -> registry.register(oldOwner, List.of(res("b"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current active identity");
        assertThat(registry.resources()).isEmpty();
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
        RegisteredPlugin owner = owner("demo");
        registry.register(owner, List.of(res("a")));
        assertThatThrownBy(() -> registry.register(owner, List.of(res("b"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("对外路径前缀全局冲突立即抛出（跨插件指向同一前缀）")
    void duplicatePrefixAcrossPluginsRejected() {
        StaticResourceRegistry registry = emptyRegistry();
        registry.register(owner("a"), List.of(res("shared")));
        assertThatThrownBy(() -> registry.register(owner("b"), List.of(res("shared"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/shared/")
                .hasMessageContaining("b");
    }

    @Test
    @DisplayName("同一插件内对外路径前缀重复也立即抛出")
    void duplicatePrefixWithinPluginRejected() {
        StaticResourceRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register(owner("demo"),
                List.of(res("dup"), res("dup"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/dup/");
    }

    @Test
    @DisplayName("非法输入拒绝：pluginId / classLoader / 列表 / classpath 位置 / 路径")
    void invalidInputRejected() {
        StaticResourceRegistry registry = emptyRegistry();
        // pluginId 空
        assertThatThrownBy(() -> registry.register(owner(" "), List.of(res("a"))))
                .isInstanceOf(IllegalStateException.class);
        // classLoader 为 null
        assertThatThrownBy(() -> new RegisteredPlugin(
                new RuntimeStaticPlugin("demo"), PluginSource.BUILT_IN, null))
                .isInstanceOf(IllegalStateException.class);
        // 列表为空
        assertThatThrownBy(() -> registry.register(owner("demo"), List.of()))
                .isInstanceOf(IllegalStateException.class);
        // classpathLocation 不以 classpath: 开头
        assertThatThrownBy(() -> registry.register(owner("demo"), List.of(
                new StaticResourceContribution("/static/a/", "/a/"))))
                .isInstanceOf(IllegalStateException.class);
        // classpathLocation 不以 / 结尾
        assertThatThrownBy(() -> registry.register(owner("demo"), List.of(
                new StaticResourceContribution("classpath:/static/a", "/a/"))))
                .isInstanceOf(IllegalStateException.class);
        // classpath 相对位置不得用绝对 URL scheme 逃出 owner CodeSource
        assertThatThrownBy(() -> registry.register(owner("demo"), List.of(
                new StaticResourceContribution("classpath:/file:C:/escape/", "/escape/"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbidden character");
        // publicPathPrefix 不以 / 开头
        assertThatThrownBy(() -> registry.register(owner("demo"), List.of(
                new StaticResourceContribution("classpath:/static/a/", "a/"))))
                .isInstanceOf(IllegalStateException.class);
        // 目录贡献 publicPathPrefix 不以 / 结尾
        assertThatThrownBy(() -> registry.register(owner("demo"), List.of(
                new StaticResourceContribution("classpath:/static/a/", "/a"))))
                .isInstanceOf(IllegalStateException.class);
        // 精确文件贡献 publicPathPrefix 以 / 结尾（应拒绝）
        assertThatThrownBy(() -> registry.register(owner("demo"), List.of(
                new StaticResourceContribution("classpath:/static/a/", "/a.html/", true))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("启动期静态资源 getter 的 AssertionError 转为无 cause 宿主异常")
    void bootPluginGetterErrorIsContained() {
        PluginRegistry plugins = new PluginRegistry(List.of(new StaticGetterErrorPlugin()));

        assertThatThrownBy(() -> new StaticResourceRegistry(plugins))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failureType=java.lang.AssertionError")
                .hasNoCause();
    }

    @Test
    @DisplayName("resources() 返回不可变快照，外部不可修改")
    void snapshotIsImmutable() {
        StaticResourceRegistry registry = emptyRegistry();
        registry.register(owner("demo"), List.of(res("a")));
        List<StaticResourceRegistry.RegisteredStaticResource> resources = registry.resources();
        assertThatThrownBy(() -> resources.add(resources.get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("外置插件对象不由登记的所有者 classloader 定义时拒绝静态资源")
    void externalStaticResourceRequiresOwnerClassLoaderToDefinePluginClass() {
        // 桥接捕获的插件 classloader（真实场景为 PF4J 插件 classloader），与插件实例 class 的 loader 不同
        ClassLoader bridgeClassLoader = new ClassLoader(StaticResourceRegistryTest.class.getClassLoader()) {};
        ExternalStaticPlugin external = new ExternalStaticPlugin();
        // 前置：本测试只有在「插件对象 class 的 loader != 桥接 classloader」时才有意义
        assertThat(external.getClass().getClassLoader()).isNotSameAs(bridgeClassLoader);

        PluginRegistry registry = new PluginRegistry(
                List.of(new CorePlaceholderPlugin()), new PluginToggleProperties(),
                new PluginDiscoveryResult(
                        List.of(new DiscoveredFeaturePlugin(
                                "ext-static", "ext-static", external, bridgeClassLoader)),
                        List.of()));
        assertThatThrownBy(() -> new StaticResourceRegistry(registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ext-static")
                .hasMessageContaining("not defined by its registered classloader");
    }

    @Test
    @DisplayName("精确文件贡献可注册，publicPathPrefix 为精确路径（不以 / 结尾），且可与目录贡献共存")
    void exactFileContributionRegistered() {
        StaticResourceRegistry registry = emptyRegistry();
        registry.register(owner("demo"), List.of(
                new StaticResourceContribution("classpath:/static/demo/", "/demo/"),
                new StaticResourceContribution("classpath:/static/demo/", "/demo/index.html", true)));
        assertThat(registry.resources())
                .extracting(r -> r.contribution().publicPathPrefix())
                .containsExactlyInAnyOrder("/demo/", "/demo/index.html");
    }

    @Test
    @DisplayName("精确文件贡献的 classpathLocation 必须是 classpath 目录（以 / 结尾）")
    void exactFileRequiresClasspathDirectory() {
        StaticResourceRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register(owner("demo"), List.of(
                new StaticResourceContribution("classpath:/static/index.html", "/index.html", true))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("精确文件 publicPathPrefix 不可为空、不以 / 开头、或以 / 结尾")
    void exactFilePublicPathValidation() {
        StaticResourceRegistry registry = emptyRegistry();
        // 不以 / 开头
        assertThatThrownBy(() -> registry.register(owner("demo"), List.of(
                new StaticResourceContribution("classpath:/static/", "index.html", true))))
                .isInstanceOf(IllegalStateException.class);
        // 以 / 结尾
        assertThatThrownBy(() -> registry.register(owner("demo"), List.of(
                new StaticResourceContribution("classpath:/static/", "/index.html/", true))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("精确文件贡献与目录贡献的 publicPathPrefix 不冲突（同一路径的不同字形各自合法）")
    void exactFileAndDirectoryPrefixesDoNotConflict() {
        StaticResourceRegistry registry = emptyRegistry();
        registry.register(owner("a"), List.of(
                new StaticResourceContribution("classpath:/static/a/", "/a/")));
        // "/a"（精确文件）与 "/a/"（目录）是不同的前缀字符串，不冲突
        assertThatCode(() -> registry.register(owner("b"), List.of(
                new StaticResourceContribution("classpath:/static/b/", "/a", true))))
                .doesNotThrowAnyException();
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
            return List.of(new StaticResourceContribution("classpath:/ext-static/", "/ext-static/"));
        }
    }

    /** 运行期注册测试使用的最小插件；其实际定义类也是被登记的资源所有者。 */
    private record RuntimeStaticPlugin(String id) implements PixivFeaturePlugin {
        @Override public String displayName() { return id + ".label"; }
        @Override public String description() { return id + ".summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
    }

    private static final class StaticGetterErrorPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "static-getter-error"; }
        @Override public String displayName() { return "static-getter-error.label"; }
        @Override public String description() { return "static-getter-error.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<StaticResourceContribution> staticResources() {
            throw new AssertionError("plugin-controlled error text");
        }
    }
}
