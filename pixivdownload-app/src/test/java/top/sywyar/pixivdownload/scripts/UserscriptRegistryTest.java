package top.sywyar.pixivdownload.scripts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.PluginRegistry;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;

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
    @DisplayName("构造时从 PluginRegistry 收集油猴脚本来源（唯下载工作台插件声明一条）")
    void collectsFromBuiltInPlugins() {
        UserscriptRegistry registry =
                new UserscriptRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        assertThat(registry.userscripts())
                .singleElement()
                .satisfies(registered -> {
                    assertThat(registered.pluginId()).isEqualTo("download-workbench");
                    assertThat(registered.contribution().classpathPattern())
                            .isEqualTo("classpath:/static/userscripts/*.user.js");
                    assertThat(registered.classLoader()).isNotNull();
                });
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
}
