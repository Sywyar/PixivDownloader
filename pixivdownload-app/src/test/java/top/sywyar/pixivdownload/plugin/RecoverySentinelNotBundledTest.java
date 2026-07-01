package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;

/**
 * recovery-sentinel 不进主程序 boot jar 的守卫：它是外置 PF4J 插件（{@code provided} 依赖、不被 app 依赖），其类与资源
 * 既不在主程序运行期类路径上，也不在内置组合根 {@link BuiltInPlugins} 内——只能作为外置 jar 放入 {@code plugins/}
 * 由运行时加载。
 *
 * <p>真实「放入外置 recovery-sentinel jar 后启动加载、以 EXTERNAL 来源接入」由
 * {@link RecoverySentinelExternalPluginIntegrationTest} 用真实插件 jar 覆盖；本类只钉「默认不携带」。
 */
@DisplayName("recovery-sentinel 不进 boot jar：类不在主程序类路径、内置组合根与下游注册中心均无该插件")
class RecoverySentinelNotBundledTest {

    private static final String SENTINEL_MAIN_CLASS =
            "top.sywyar.pixivdownload.recoverysentinel.RecoverySentinelPf4jPlugin";
    private static final String SENTINEL_FEATURE_CLASS =
            "top.sywyar.pixivdownload.recoverysentinel.RecoverySentinelPlugin";

    private final PluginRegistry registry = new PluginRegistry(BuiltInPlugins.createAll());

    @Test
    @DisplayName("主程序类路径加载不到 recovery-sentinel 的 PF4J 主类与功能插件类（不在 boot jar / classes 内）")
    void recoverySentinelClassesAreNotOnHostClasspath() {
        ClassLoader hostClassLoader = getClass().getClassLoader();
        assertThatThrownBy(() -> Class.forName(SENTINEL_MAIN_CLASS, false, hostClassLoader))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName(SENTINEL_FEATURE_CLASS, false, hostClassLoader))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("内置组合根不含 recovery-sentinel，活动 / 安装快照里都没有它")
    void builtInRegistryHasNoRecoverySentinel() {
        assertThat(BuiltInPlugins.createAll()).extracting(PixivFeaturePlugin::id).doesNotContain("recovery-sentinel");
        assertThat(BuiltInPlugins.isBuiltIn("recovery-sentinel")).isFalse();
        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).doesNotContain("recovery-sentinel");
        assertThat(registry.allPlugins()).extracting(PixivFeaturePlugin::id).doesNotContain("recovery-sentinel");
        assertThat(registry.find("recovery-sentinel")).isEmpty();
    }

    @Test
    @DisplayName("route / navigation / static / i18n 注册中心均无 recovery-sentinel 贡献")
    void downstreamRegistriesHaveNoRecoverySentinelContribution() {
        assertThat(new RouteAccessRegistry(registry).routes())
                .noneMatch(r -> r.pluginId().equals("recovery-sentinel"));
        assertThat(new NavigationRegistry(registry).navigation())
                .noneMatch(n -> n.pluginId().equals("recovery-sentinel"));
        assertThat(new StaticResourceRegistry(registry).resources())
                .noneMatch(s -> s.pluginId().equals("recovery-sentinel"));
        assertThat(new WebI18nBundleRegistry(registry).resolve("recovery-sentinel")).isNull();
    }
}
