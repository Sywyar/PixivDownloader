package top.sywyar.pixivdownload.scripts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("UserscriptRegistry 油猴脚本来源注册中心")
class UserscriptRegistryTest {

    private static final ClassLoader CL = UserscriptRegistryTest.class.getClassLoader();

    private static UserscriptRegistry emptyRegistry() {
        return new UserscriptRegistry(new PluginRegistry(List.of()));
    }

    private static UserscriptContribution uscript(String pluginId, String pattern) {
        return new UserscriptContribution(pluginId, pattern);
    }

    @Test
    @DisplayName("core-only 内置插件清单不收集油猴脚本来源")
    void collectsFromBuiltInPlugins() {
        UserscriptRegistry registry =
                new UserscriptRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        assertThat(registry.userscripts()).isEmpty();
    }

    @Test
    @DisplayName("register → unregister → 再 register 后快照与首次注册一致（可逆性）")
    void registerUnregisterRoundTrip() {
        UserscriptRegistry registry = emptyRegistry();
        List<UserscriptContribution> items = List.of(uscript("demo", "classpath:/x/*.user.js"));
        registry.register("demo", CL, items);
        List<UserscriptRegistry.RegisteredUserscript> first = registry.userscripts();
        registry.unregister("demo");
        assertThat(registry.userscripts()).isEmpty();
        registry.register("demo", CL, items);
        assertThat(registry.userscripts()).isEqualTo(first);
    }

    @Test
    @DisplayName("unregister 对未注册过来源的 pluginId 静默返回（统一卸载流程对每个插件都会调用）")
    void unregisterUnknownPluginIsSilent() {
        UserscriptRegistry registry = emptyRegistry();
        registry.unregister("never-registered");
        assertThat(registry.userscripts()).isEmpty();
    }

    @Test
    @DisplayName("同一 pluginId 重复注册立即抛出")
    void duplicatePluginRegistrationRejected() {
        UserscriptRegistry registry = emptyRegistry();
        registry.register("demo", CL, List.of(uscript("demo", "classpath:/a/*.user.js")));
        assertThatThrownBy(() -> registry.register("demo", CL, List.of(uscript("demo", "classpath:/b/*.user.js"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("扫描模式全局冲突立即抛出（跨插件指向同一模式）")
    void duplicatePatternAcrossPluginsRejected() {
        UserscriptRegistry registry = emptyRegistry();
        registry.register("a", CL, List.of(uscript("a", "classpath:/shared/*.user.js")));
        assertThatThrownBy(() -> registry.register("b", CL, List.of(uscript("b", "classpath:/shared/*.user.js"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classpath:/shared/*.user.js")
                .hasMessageContaining("b");
    }

    @Test
    @DisplayName("同一插件内扫描模式重复也立即抛出")
    void duplicatePatternWithinPluginRejected() {
        UserscriptRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register("demo", CL, List.of(
                uscript("demo", "classpath:/dup/*.user.js"),
                uscript("demo", "classpath:/dup/*.user.js"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classpath:/dup/*.user.js");
    }

    @Test
    @DisplayName("非法输入拒绝：pluginId / classLoader / 列表 / 扫描模式非空，pluginId 一致性")
    void invalidInputRejected() {
        UserscriptRegistry registry = emptyRegistry();
        assertThatThrownBy(() -> registry.register(" ", CL, List.of(uscript(" ", "classpath:/a/*.user.js"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", null, List.of(uscript("demo", "classpath:/a/*.user.js"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", CL, List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", CL, List.of(uscript("demo", " "))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("demo", CL, List.of(uscript("other", "classpath:/a/*.user.js"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mismatch");
    }

    @Test
    @DisplayName("userscripts() 返回不可变快照，外部不可修改")
    void snapshotIsImmutable() {
        UserscriptRegistry registry = emptyRegistry();
        registry.register("demo", CL, List.of(uscript("demo", "classpath:/a/*.user.js")));
        List<UserscriptRegistry.RegisteredUserscript> userscripts = registry.userscripts();
        assertThatThrownBy(() -> userscripts.add(new UserscriptRegistry.RegisteredUserscript(
                "x", uscript("x", "classpath:/x/*.user.js"), CL)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("外置插件油猴脚本来源用桥接提供的 classloader 注册，而非插件对象 class 的 loader")
    void externalUserscriptUsesBridgeClassLoaderNotPluginClassLoader() {
        // 桥接捕获的插件 classloader（真实场景为 PF4J 插件 classloader），与插件实例 class 的 loader 不同
        ClassLoader bridgeClassLoader = new ClassLoader(UserscriptRegistryTest.class.getClassLoader()) {};
        ExternalUserscriptPlugin external = new ExternalUserscriptPlugin();
        // 前置：本测试只有在「插件对象 class 的 loader != 桥接 classloader」时才有意义
        assertThat(external.getClass().getClassLoader()).isNotSameAs(bridgeClassLoader);

        PluginDiscoveryResult discovery = new PluginDiscoveryResult(
                List.of(new DiscoveredFeaturePlugin("ext-script", external, bridgeClassLoader)), List.of());
        PluginRegistry registry = new PluginRegistry(
                List.of(new CorePlaceholderPlugin()), new PluginToggleProperties(), discoveryProvider(discovery));
        UserscriptRegistry registry2 = new UserscriptRegistry(registry);

        UserscriptRegistry.RegisteredUserscript registered = registry2.userscripts().stream()
                .filter(r -> r.pluginId().equals("ext-script")).findFirst().orElseThrow();
        assertThat(registered.classLoader())
                .as("应使用 RegisteredPlugin.classLoader()（桥接 classloader），而非 plugin.getClass().getClassLoader()")
                .isSameAs(bridgeClassLoader)
                .isNotSameAs(external.getClass().getClassLoader());
    }

    /** 把单个发现结果包装为 {@link ObjectProvider}，驱动 {@link PluginRegistry} 的 Spring 构造器（scripts 包不可见包内构造器）。 */
    private static ObjectProvider<PluginDiscoveryResult> discoveryProvider(PluginDiscoveryResult result) {
        return new ObjectProvider<>() {
            @Override
            public PluginDiscoveryResult getObject() {
                return result;
            }
        };
    }

    /** 最小核心插件占位（无油猴脚本来源），仅作对照。 */
    private static final class CorePlaceholderPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "core"; }
        @Override public String displayName() { return "core.label"; }
        @Override public String description() { return "core.summary"; }
        @Override public PluginKind kind() { return PluginKind.CORE; }
    }

    /** 外置功能插件：声明一条油猴脚本来源，用于验证注册中心采用桥接 classloader。 */
    private static final class ExternalUserscriptPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "ext-script"; }
        @Override public String displayName() { return "ext-script.label"; }
        @Override public String description() { return "ext-script.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<UserscriptContribution> userscripts() {
            return List.of(new UserscriptContribution("ext-script", "classpath:/ext-script/*.user.js"));
        }
    }
}
