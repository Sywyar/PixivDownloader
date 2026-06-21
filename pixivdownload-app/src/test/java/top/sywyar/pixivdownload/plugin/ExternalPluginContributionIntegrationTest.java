package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.runtime.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.PluginDiscoveryResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外置插件贡献接入与可逆性集成测试：验证外置插件经发现桥接进入 {@link PluginRegistry} 后，其
 * schema / 静态资源 / 导航等 contribution 经各下游注册中心（与内置插件同一条聚合路径）启动期注册到位，
 * 且按 pluginId 注销后不残留——证明「外置插件贡献进入 schema / route / static / i18n / navigation /
 * script registry 的路径打通」与可逆注册对外置来源同样成立（下游各注册中心共用同一不可变快照 + 按 pluginId
 * 注销的形态，此处取 schema / static / navigation 三个代表性注册中心覆盖）。
 */
@DisplayName("外置插件 contribution 接入下游注册中心与可逆注销")
class ExternalPluginContributionIntegrationTest {

    private static PluginRegistry registryWithExternal(PixivFeaturePlugin external) {
        ClassLoader pluginClassLoader = external.getClass().getClassLoader();
        PluginDiscoveryResult discovery = new PluginDiscoveryResult(
                List.of(new DiscoveredFeaturePlugin("ext-demo-pack", external, pluginClassLoader)), List.of());
        return new PluginRegistry(List.of(new MinimalCorePlugin()), new PluginToggleProperties(), discovery);
    }

    @Test
    @DisplayName("外置插件静态资源进入 StaticResourceRegistry（classloader-aware），注销后不残留")
    void externalStaticResourcesRegisterAndUnregister() {
        PluginRegistry registry = registryWithExternal(new ExternalDemoPlugin());
        StaticResourceRegistry staticResources = new StaticResourceRegistry(registry);

        StaticResourceRegistry.RegisteredStaticResource registered = staticResources.resources().stream()
                .filter(r -> r.pluginId().equals("ext-demo")).findFirst().orElseThrow();
        assertThat(registered.contribution().publicPathPrefix()).isEqualTo("/ext-demo/");
        // classloader-aware：解析用 classloader 是发现桥接提供的那个（本测试里桥接 cl 即取插件类 loader，故二者一致；
        // 「桥接 cl 与插件类 loader 不同」的判别用例见 StaticResourceRegistryTest / WebI18nBundleRegistryTest）
        assertThat(registered.classLoader()).isSameAs(ExternalDemoPlugin.class.getClassLoader());

        staticResources.unregister("ext-demo");
        assertThat(staticResources.resources()).noneMatch(r -> r.pluginId().equals("ext-demo"));
    }

    @Test
    @DisplayName("外置插件导航项进入 NavigationRegistry，注销后不残留")
    void externalNavigationRegistersAndUnregister() {
        PluginRegistry registry = registryWithExternal(new ExternalDemoPlugin());
        NavigationRegistry navigation = new NavigationRegistry(registry);

        assertThat(navigation.navigation()).anyMatch(n -> n.pluginId().equals("ext-demo"));

        navigation.unregister("ext-demo");
        assertThat(navigation.navigation()).noneMatch(n -> n.pluginId().equals("ext-demo"));
    }

    @Test
    @DisplayName("外置插件 schema 经 allPlugins 进入 DatabaseSchemaRegistry，注销后不残留")
    void externalSchemaRegistersAndUnregister() {
        PluginRegistry registry = registryWithExternal(new ExternalDemoPlugin());
        DatabaseSchemaRegistry schema = new DatabaseSchemaRegistry(registry);

        assertThat(schema.contributions()).anyMatch(c -> c.ownerPluginId().equals("ext-demo"));

        schema.unregister("ext-demo");
        assertThat(schema.contributions()).noneMatch(c -> c.ownerPluginId().equals("ext-demo"));
    }

    @Test
    @DisplayName("从 PluginRegistry 注销外置插件后，重新构建的 DatabaseSchemaRegistry 不再合并其 schema")
    void unregisterFromPluginRegistryDropsSchemaOnRebuild() {
        PluginRegistry registry = registryWithExternal(new ExternalDemoPlugin());
        // 注销前：schema 合并经 allPlugins() 覆盖外置插件
        assertThat(new DatabaseSchemaRegistry(registry).contributions())
                .anyMatch(c -> c.ownerPluginId().equals("ext-demo"));

        registry.unregister("ext-demo");

        // 注销后 allPlugins() 不再含外置插件 → 重新构建的 schema registry 看不到其 schema
        assertThat(registry.allPlugins()).extracting(PixivFeaturePlugin::id).doesNotContain("ext-demo");
        assertThat(new DatabaseSchemaRegistry(registry).contributions())
                .noneMatch(c -> c.ownerPluginId().equals("ext-demo"));
    }

    /** 最小核心插件占位（无 contribution）：仅为满足注册中心需要一个内置插件作为对照。 */
    private static final class MinimalCorePlugin implements PixivFeaturePlugin {
        @Override
        public String id() {
            return "core";
        }

        @Override
        public String displayName() {
            return "core.label";
        }

        @Override
        public String description() {
            return "core.summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.CORE;
        }
    }

    /** 外置功能插件：声明 schema / 静态资源 / 导航 / i18n 各一，只用 plugin.api 契约类型。 */
    private static final class ExternalDemoPlugin implements PixivFeaturePlugin {
        @Override
        public String id() {
            return "ext-demo";
        }

        @Override
        public String displayName() {
            return "ext-demo.nav.label";
        }

        @Override
        public String description() {
            return "ext-demo.summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }

        @Override
        public List<SchemaContribution> schema() {
            return List.of(new SchemaContribution("ext-demo",
                    List.of(new TableSpec("ext_demo_table",
                            List.of(new ColumnSpec("id", "INTEGER", true, null, 1)), List.of())),
                    List.of(), List.of(), List.of()));
        }

        @Override
        public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution("ext-demo", "classpath:/ext-demo-static/", "/ext-demo/"));
        }

        @Override
        public List<NavigationContribution> navigation() {
            return List.of(new NavigationContribution("ext-demo-nav", "app.top", "ext-demo.nav.label",
                    "/ext-demo/index.html", null, AccessPolicy.ADMIN, 500));
        }

        @Override
        public List<I18nContribution> i18n() {
            return List.of(new I18nContribution("ext-demo", "i18n.web.ext-demo"));
        }
    }
}
