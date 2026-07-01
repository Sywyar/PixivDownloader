package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;
import top.sywyar.pixivdownload.scripts.ScriptRegistry;
import top.sywyar.pixivdownload.scripts.UserscriptRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionRegistrar;

/**
 * {@link PluginWebContributionRegistrar} 单测：验证把一个插件的六类 web 贡献
 * （route / static / i18n / navigation / ui-slot / userscript）按 pluginId 统一接入 / 撤销，且
 * <ul>
 *   <li>注销后六类快照均无残留，i18n bundle 与脚本层（{@link ScriptRegistry}）也随之刷新无残留；</li>
 *   <li>注销后路由「未声明」——即 {@code AuthFilter} 对其 URL「未声明即 404」（静态资源回收靠此、与禁用语义一致）；</li>
 *   <li>「注册 → 注销 → 再注册」后各注册中心快照与首次一致；</li>
 *   <li>冲突（i18n namespace 重复 / ui-slot slotId 重复）在注册期 fail-fast，且本插件已接入的其它注册中心全部回滚（原子）；</li>
 *   <li>静态资源 / i18n / userscript 解析用 classloader 是插件来源 classloader（classloader-aware）；</li>
 *   <li>对外置插件的注册 / 注销不改动内置插件已接入的贡献。</li>
 * </ul>
 * 真实外置 stats 插件经完整上下文的端到端注销不可达验证见 {@code StatsExternalPluginBootContextTest}。
 */
@DisplayName("插件 web 贡献统一注册 / 注销（route/static/i18n/navigation/ui-slot/userscript）")
class PluginWebContributionRegistrarTest {

    private static final ClassLoader CL = PluginWebContributionRegistrarTest.class.getClassLoader();

    @Test
    @DisplayName("register 把六类贡献接入各注册中心（classloader-aware），ScriptRegistry 刷新出脚本")
    void registerExposesAllSixContributions() {
        Harness h = emptyHarness();

        h.registrar.register(external(new WebDemoPlugin()));

        assertThat(h.route.routes()).filteredOn(r -> r.pluginId().equals("web-demo"))
                .extracting(r -> r.route().pathPattern())
                .containsExactlyInAnyOrder("/web-demo.html", "/web-demo/**");

        StaticResourceRegistry.RegisteredStaticResource staticResource = h.staticRes.resources().stream()
                .filter(s -> s.pluginId().equals("web-demo")).findFirst().orElseThrow();
        assertThat(staticResource.contribution().publicPathPrefix()).isEqualTo("/web-demo/");
        assertThat(staticResource.classLoader()).isSameAs(CL);

        WebI18nBundleRegistry.RegisteredBundle bundle = h.i18n.resolve("web-demo");
        assertThat(bundle).isNotNull();
        assertThat(bundle.classLoader()).isSameAs(CL);

        assertThat(h.nav.navigation()).anyMatch(n -> n.pluginId().equals("web-demo"));

        WebUiSlotRegistry.RegisteredUiSlot uiSlot = h.uiSlot.slots().stream()
                .filter(s -> s.pluginId().equals("web-demo")).findFirst().orElseThrow();
        assertThat(uiSlot.slot().target()).isEqualTo("demo-anchor");
        assertThat(uiSlot.slot().moduleUrl()).isEqualTo("/web-demo/slot.js");

        UserscriptRegistry.RegisteredUserscript userscript = h.userscripts.userscripts().stream()
                .filter(u -> u.pluginId().equals("web-demo")).findFirst().orElseThrow();
        assertThat(userscript.classLoader()).isSameAs(CL);

        // userscript 来源接入后 ScriptRegistry 经声明方 classloader 扫到本插件的脚本（注册的脚本来源被刷新）。
        assertThat(h.scripts.getScripts()).anyMatch(s -> s.id().equals("sample-plugin"));
    }

    @Test
    @DisplayName("unregister 后六类快照与脚本层均无残留；路由「未声明」即 AuthFilter 404")
    void unregisterLeavesNoResidueAndStaticBecomesUndeclared() {
        Harness h = emptyHarness();
        h.registrar.register(external(new WebDemoPlugin()));
        // 前置：静态资源 URL 已被路由声明（AuthFilter 放行后才到 ResourceHandler）
        assertThat(h.route.isDeclared("/web-demo/page.css", HttpMethod.GET)).isTrue();

        h.registrar.unregister("web-demo", CL);

        // 路由注销 → AuthFilter 对其 URL「未声明即 404」（与禁用语义一致，静态资源不可达）
        assertThat(h.route.isDeclared("/web-demo/page.css", HttpMethod.GET)).isFalse();
        assertThat(h.route.routes()).noneMatch(r -> r.pluginId().equals("web-demo"));
        assertThat(h.staticRes.resources()).noneMatch(s -> s.pluginId().equals("web-demo"));
        assertThat(h.i18n.resolve("web-demo")).isNull();
        assertThat(h.i18n.bundles()).noneMatch(b -> b.pluginId().equals("web-demo"));
        assertThat(h.nav.navigation()).noneMatch(n -> n.pluginId().equals("web-demo"));
        assertThat(h.uiSlot.slots()).noneMatch(s -> s.pluginId().equals("web-demo"));
        assertThat(h.userscripts.userscripts()).noneMatch(u -> u.pluginId().equals("web-demo"));
        // 脚本层刷新：被注销插件的油猴脚本不再残留
        assertThat(h.scripts.getScripts()).noneMatch(s -> s.id().equals("sample-plugin"));
    }

    @Test
    @DisplayName("注册 → 注销 → 再注册后各注册中心快照与首次一致")
    void registerUnregisterReRegisterIsConsistent() {
        Harness h = emptyHarness();
        PixivFeaturePlugin plugin = new WebDemoPlugin();

        h.registrar.register(external(plugin));
        Fingerprint first = Fingerprint.of(h);

        h.registrar.unregister("web-demo", CL);
        assertThat(Fingerprint.of(h)).isEqualTo(Fingerprint.empty());

        h.registrar.register(external(plugin));
        assertThat(Fingerprint.of(h)).isEqualTo(first);
    }

    @Test
    @DisplayName("i18n namespace 冲突注册期 fail-fast，且本插件已接入的 route/static 原子回滚")
    void conflictFailsFastAndRollsBackAtomically() {
        Harness h = emptyHarness();
        h.registrar.register(external(new WebDemoPlugin())); // 占用 namespace "web-demo"

        assertThatThrownBy(() -> h.registrar.register(external(new ConflictingI18nPlugin())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("web-demo");

        // 原子回滚：冲突插件先接入的 route / static 不残留
        assertThat(h.route.routes()).noneMatch(r -> r.pluginId().equals("web-demo-2"));
        assertThat(h.staticRes.resources()).noneMatch(s -> s.pluginId().equals("web-demo-2"));
        // 原有插件不受影响
        assertThat(h.i18n.resolve("web-demo")).isNotNull();
        assertThat(h.route.routes()).anyMatch(r -> r.pluginId().equals("web-demo"));
    }

    @Test
    @DisplayName("ui-slot slotId 冲突注册期 fail-fast，且本插件已接入的 route/static/i18n/navigation 原子回滚")
    void uiSlotConflictFailsFastAndRollsBackAtomically() {
        Harness h = emptyHarness();
        h.registrar.register(external(new WebDemoPlugin())); // 占用 slotId "web-demo.slot"

        assertThatThrownBy(() -> h.registrar.register(external(new ConflictingUiSlotPlugin())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("web-demo.slot");

        // 原子回滚：冲突插件先接入的 route / static / navigation 不残留（ui-slot 注册在它们之后）
        assertThat(h.route.routes()).noneMatch(r -> r.pluginId().equals("web-demo-3"));
        assertThat(h.staticRes.resources()).noneMatch(s -> s.pluginId().equals("web-demo-3"));
        assertThat(h.nav.navigation()).noneMatch(n -> n.pluginId().equals("web-demo-3"));
        // 原有插件不受影响
        assertThat(h.uiSlot.slots()).anyMatch(s -> s.pluginId().equals("web-demo"));
        assertThat(h.route.routes()).anyMatch(r -> r.pluginId().equals("web-demo"));
    }

    @Test
    @DisplayName("对外置插件的注册 / 注销不改动内置插件已接入的贡献")
    void builtInContributionsUnaffected() {
        Harness h = harness(new PluginRegistry(BuiltInPlugins.createAll()));
        Fingerprint builtIn = Fingerprint.of(h);
        int builtInScripts = h.scripts.getScripts().size();

        h.registrar.register(external(new WebDemoPlugin()));
        h.registrar.unregister("web-demo", CL);

        assertThat(Fingerprint.of(h)).isEqualTo(builtIn);
        assertThat(h.scripts.getScripts()).hasSize(builtInScripts);
    }

    // --- 夹具 ---

    private record Harness(RouteAccessRegistry route, StaticResourceRegistry staticRes,
                           WebI18nBundleRegistry i18n, NavigationRegistry nav, WebUiSlotRegistry uiSlot,
                           UserscriptRegistry userscripts, ScriptRegistry scripts,
                           PluginWebContributionRegistrar registrar) {
    }

    private static Harness harness(PluginRegistry base) {
        RouteAccessRegistry route = new RouteAccessRegistry(base);
        StaticResourceRegistry staticRes = new StaticResourceRegistry(base);
        WebI18nBundleRegistry i18n = new WebI18nBundleRegistry(base);
        NavigationRegistry nav = new NavigationRegistry(base);
        WebUiSlotRegistry uiSlot = new WebUiSlotRegistry(base);
        UserscriptRegistry userscripts = new UserscriptRegistry(base);
        ScriptRegistry scripts = new ScriptRegistry(TestI18nBeans.appMessages(), userscripts);
        PluginWebContributionRegistrar registrar = new PluginWebContributionRegistrar(
                route, staticRes, i18n, nav, uiSlot, userscripts, scripts);
        return new Harness(route, staticRes, i18n, nav, uiSlot, userscripts, scripts, registrar);
    }

    private static Harness emptyHarness() {
        return harness(new PluginRegistry(List.of()));
    }

    private static PluginRegistry.RegisteredPlugin external(PixivFeaturePlugin plugin) {
        return new PluginRegistry.RegisteredPlugin(plugin, PluginSource.EXTERNAL, CL);
    }

    /** 六类注册中心快照 + 脚本层的稳定投影，用于「注册 → 注销 → 再注册」一致性与「内置不变」断言。 */
    private record Fingerprint(List<String> routes, List<String> staticPrefixes, List<String> namespaces,
                              List<String> navIds, List<String> uiSlotIds, List<String> userscriptPatterns,
                              List<String> scriptIds) {

        static Fingerprint of(Harness h) {
            return new Fingerprint(
                    h.route.routes().stream().map(r -> r.route().pathPattern()).sorted().toList(),
                    h.staticRes.resources().stream().map(s -> s.contribution().publicPathPrefix()).sorted().toList(),
                    h.i18n.bundles().stream().map(b -> b.contribution().namespace()).sorted().toList(),
                    h.nav.navigation().stream().map(n -> n.navigation().id()).sorted().toList(),
                    h.uiSlot.slots().stream().map(s -> s.slot().slotId()).sorted().toList(),
                    h.userscripts.userscripts().stream().map(u -> u.contribution().classpathPattern()).sorted().toList(),
                    h.scripts.getScripts().stream().map(s -> s.id()).sorted().toList());
        }

        static Fingerprint empty() {
            return new Fingerprint(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    /** 外置功能插件：贡献 route / static / i18n / navigation / userscript 各非空，仅用 plugin.api 契约类型。 */
    private static final class WebDemoPlugin implements PixivFeaturePlugin {
        @Override
        public String id() {
            return "web-demo";
        }

        @Override
        public String displayName() {
            return "web-demo.nav.label";
        }

        @Override
        public String description() {
            return "web-demo.summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }

        @Override
        public List<WebRouteContribution> routes() {
            return List.of(WebRouteContribution.admin("/web-demo.html"),
                    WebRouteContribution.admin("/web-demo/**"));
        }

        @Override
        public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution(
                    "web-demo", "classpath:/test-userscripts/", "/web-demo/"));
        }

        @Override
        public List<I18nContribution> i18n() {
            return List.of(new I18nContribution("web-demo", "i18n.web.web-demo"));
        }

        @Override
        public List<NavigationContribution> navigation() {
            return List.of(new NavigationContribution("web-demo-nav", "app.top", "ns", "web-demo.nav.label",
                    "/web-demo.html", null, AccessPolicy.ADMIN, 500));
        }

        @Override
        public List<WebUiSlotContribution> uiSlots() {
            return List.of(new WebUiSlotContribution(
                    "web-demo", "web-demo.slot", "demo-anchor", "/web-demo/slot.js", 10));
        }

        @Override
        public List<UserscriptContribution> userscripts() {
            return List.of(new UserscriptContribution("web-demo", "classpath:/test-userscripts/*.user.js"));
        }
    }

    /**
     * 另一个外置插件：route / static 唯一，但 i18n namespace 与 {@link WebDemoPlugin} 冲突（同 "web-demo"）。
     * 用于验证 register 在 i18n 步骤 fail-fast 时，已接入的 route / static 被原子回滚。
     */
    private static final class ConflictingI18nPlugin implements PixivFeaturePlugin {
        @Override
        public String id() {
            return "web-demo-2";
        }

        @Override
        public String displayName() {
            return "web-demo-2.nav.label";
        }

        @Override
        public String description() {
            return "web-demo-2.summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }

        @Override
        public List<WebRouteContribution> routes() {
            return List.of(WebRouteContribution.admin("/web-demo-2/**"));
        }

        @Override
        public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution(
                    "web-demo-2", "classpath:/test-userscripts/", "/web-demo-2/"));
        }

        @Override
        public List<I18nContribution> i18n() {
            return List.of(new I18nContribution("web-demo", "i18n.web.web-demo-conflict"));
        }
    }

    /**
     * 又一个外置插件：route / static / navigation 唯一，但 ui-slot slotId 与 {@link WebDemoPlugin} 冲突
     * （同 "web-demo.slot"）。用于验证 register 在 ui-slot 步骤 fail-fast 时，已接入的 route / static / navigation
     * 被原子回滚。
     */
    private static final class ConflictingUiSlotPlugin implements PixivFeaturePlugin {
        @Override
        public String id() {
            return "web-demo-3";
        }

        @Override
        public String displayName() {
            return "web-demo-3.nav.label";
        }

        @Override
        public String description() {
            return "web-demo-3.summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }

        @Override
        public List<WebRouteContribution> routes() {
            return List.of(WebRouteContribution.admin("/web-demo-3/**"));
        }

        @Override
        public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution(
                    "web-demo-3", "classpath:/test-userscripts/", "/web-demo-3/"));
        }

        @Override
        public List<NavigationContribution> navigation() {
            return List.of(new NavigationContribution("web-demo-3-nav", "app.top", "ns", "web-demo-3.nav.label",
                    "/web-demo-3.html", null, AccessPolicy.ADMIN, 500));
        }

        @Override
        public List<WebUiSlotContribution> uiSlots() {
            return List.of(new WebUiSlotContribution(
                    "web-demo-3", "web-demo.slot", "demo-anchor-3", "/web-demo-3/slot.js", 10));
        }
    }
}
