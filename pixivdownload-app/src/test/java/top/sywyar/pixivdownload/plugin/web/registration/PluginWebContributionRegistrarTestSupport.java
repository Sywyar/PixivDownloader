package top.sywyar.pixivdownload.plugin.web.registration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.TestDownloadTypeDescriptors;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.plugin.api.web.DrilldownContribution;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.LandingContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.PageSectionContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContext;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestGenerationDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLease;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLeaseRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestOwner;
import top.sywyar.pixivdownload.plugin.registry.download.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.DrilldownRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.LandingRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.PageSectionRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.StartupRouteRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.WebUiSlotRegistry;
import top.sywyar.pixivdownload.plugin.web.resource.PluginOwnedWebAssetValidator;
import top.sywyar.pixivdownload.plugin.web.registration.PluginWebContributionHandle;
import top.sywyar.pixivdownload.plugin.web.registration.PluginWebContributionRegistrar;
import top.sywyar.pixivdownload.scripts.ScriptRegistry;
import top.sywyar.pixivdownload.scripts.UserscriptRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PluginWebContributionRegistrar} 单测：验证把一个插件的十类 web 贡献
 * （route / static / i18n / navigation / startup-route / landing / page-section / drilldown / ui-slot /
 * userscript）与下载扩展按精确 owner 统一接入 / 撤销，且
 * <ul>
 *   <li>注销后十类 web 快照与下载扩展快照均无残留，i18n bundle 与脚本层也随之刷新无残留；</li>
 *   <li>注销后路由「未声明」——即 {@code AuthFilter} 对其 URL「未声明即 404」（静态资源回收靠此、与禁用语义一致）；</li>
 *   <li>「注册 → 注销 → 再注册」后各注册中心快照与首次一致；</li>
 *   <li>冲突（i18n namespace 重复 / ui-slot slotId 重复）在注册期 fail-fast，且本插件已接入的其它注册中心全部回滚（原子）；</li>
 *   <li>静态资源 / i18n / userscript 解析用 classloader 是插件来源 classloader（classloader-aware）；</li>
 *   <li>对外置插件的注册 / 注销不改动内置插件已接入的贡献。</li>
 * </ul>
 * 真实外置 stats 插件经完整上下文的端到端注销不可达验证见 {@code StatsExternalPluginBootContextTest}。
 */
abstract class PluginWebContributionRegistrarTestSupport {

    protected static final ClassLoader CL = PluginWebContributionRegistrarTest.class.getClassLoader();

    protected static void assertLifecycleContributions(Harness h, String pluginId, boolean expected) {
        assertLifecycleContributions(
                h.startup, h.landing, h.pageSections, h.drilldowns, pluginId, expected);
    }

    protected static void assertLifecycleContributions(LifecycleHarness h, String pluginId, boolean expected) {
        assertLifecycleContributions(
                h.startup, h.landing, h.pageSections, h.drilldowns, pluginId, expected);
    }

    protected static void assertLifecycleContributions(
            StartupRouteRegistry startup,
            LandingRegistry landing,
            PageSectionRegistry pageSections,
            DrilldownRegistry drilldowns,
            String pluginId,
            boolean expected) {
        assertThat(startup.startupRoutes().stream().anyMatch(item -> item.pluginId().equals(pluginId)))
                .isEqualTo(expected);
        assertThat(landing.landings().stream().anyMatch(item -> item.pluginId().equals(pluginId)))
                .isEqualTo(expected);
        assertThat(pageSections.sections().stream().anyMatch(item -> item.pluginId().equals(pluginId)))
                .isEqualTo(expected);
        assertThat(drilldowns.drilldowns().stream().anyMatch(item -> item.pluginId().equals(pluginId)))
                .isEqualTo(expected);
    }

    protected static PageSectionContribution lifecycleSection(String sectionId) {
        return new PageSectionContribution(
                sectionId, "lifecycle.sections", "lifecycle", "section.title",
                null, null, null, null, null, null, AccessPolicy.ADMIN, 10);
    }

    // --- 夹具 ---

    protected static final class Harness {
        protected final RouteAccessRegistry route;
        protected final StaticResourceRegistry staticRes;
        protected final WebI18nBundleRegistry i18n;
        protected final NavigationRegistry nav;
        protected final WebUiSlotRegistry uiSlot;
        protected final StartupRouteRegistry startup;
        protected final LandingRegistry landing;
        protected final PageSectionRegistry pageSections;
        protected final DrilldownRegistry drilldowns;
        protected final UserscriptRegistry userscripts;
        protected final ScriptRegistry scripts;
        protected final PluginWebContributionRegistrar registrar;

        protected Harness(
                RouteAccessRegistry route,
                StaticResourceRegistry staticRes,
                WebI18nBundleRegistry i18n,
                NavigationRegistry nav,
                WebUiSlotRegistry uiSlot,
                StartupRouteRegistry startup,
                LandingRegistry landing,
                PageSectionRegistry pageSections,
                DrilldownRegistry drilldowns,
                UserscriptRegistry userscripts,
                ScriptRegistry scripts,
                PluginWebContributionRegistrar registrar) {
            this.route = route;
            this.staticRes = staticRes;
            this.i18n = i18n;
            this.nav = nav;
            this.uiSlot = uiSlot;
            this.startup = startup;
            this.landing = landing;
            this.pageSections = pageSections;
            this.drilldowns = drilldowns;
            this.userscripts = userscripts;
            this.scripts = scripts;
            this.registrar = registrar;
        }
    }

    protected static final class DownloadHarness {
        protected final PluginRegistry plugins;
        protected final RouteAccessRegistry route;
        protected final StaticResourceRegistry staticResources;
        protected final DownloadExtensionRegistry downloads;
        protected final PluginRequestLeaseRegistry requestLeases;
        protected final PluginWebContributionRegistrar registrar;

        protected DownloadHarness(
                PluginRegistry plugins,
                RouteAccessRegistry route,
                StaticResourceRegistry staticResources,
                DownloadExtensionRegistry downloads,
                PluginRequestLeaseRegistry requestLeases,
                PluginWebContributionRegistrar registrar) {
            this.plugins = plugins;
            this.route = route;
            this.staticResources = staticResources;
            this.downloads = downloads;
            this.requestLeases = requestLeases;
            this.registrar = registrar;
        }
    }

    protected static final class LifecycleHarness {
        protected final StartupRouteRegistry startup;
        protected final LandingRegistry landing;
        protected final PageSectionRegistry pageSections;
        protected final DrilldownRegistry drilldowns;
        protected final PluginWebContributionRegistrar registrar;

        protected LifecycleHarness(
                StartupRouteRegistry startup,
                LandingRegistry landing,
                PageSectionRegistry pageSections,
                DrilldownRegistry drilldowns,
                PluginWebContributionRegistrar registrar) {
            this.startup = startup;
            this.landing = landing;
            this.pageSections = pageSections;
            this.drilldowns = drilldowns;
            this.registrar = registrar;
        }
    }

    protected static Harness harness(PluginRegistry base) {
        RouteAccessRegistry route = new RouteAccessRegistry(base);
        StaticResourceRegistry staticRes = new AutoRegisteringStaticResourceRegistry(base);
        WebI18nBundleRegistry i18n = new WebI18nBundleRegistry(base);
        NavigationRegistry nav = new NavigationRegistry(base);
        WebUiSlotRegistry uiSlot = new WebUiSlotRegistry(base);
        StartupRouteRegistry startup = new StartupRouteRegistry(base);
        LandingRegistry landing = new LandingRegistry(base);
        PageSectionRegistry pageSections = new PageSectionRegistry(base);
        DrilldownRegistry drilldowns = new DrilldownRegistry(base);
        UserscriptRegistry userscripts = new UserscriptRegistry(base);
        ScriptRegistry scripts = new ScriptRegistry(TestI18nBeans.appMessages(), userscripts);
        PluginWebContributionRegistrar registrar = new PluginWebContributionRegistrar(
                route, staticRes, i18n, nav, uiSlot, userscripts, scripts,
                startup, landing, pageSections, drilldowns);
        return new Harness(route, staticRes, i18n, nav, uiSlot, startup, landing, pageSections, drilldowns,
                userscripts, scripts, registrar);
    }

    protected static Harness emptyHarness() {
        return harness(new PluginRegistry(List.of()));
    }

    /** 独立 registrar 测试构造器的 owner 仍先接入真实 PluginRegistry，再消费 prepared static token。 */
    protected static final class AutoRegisteringStaticResourceRegistry extends StaticResourceRegistry {
        protected final PluginRegistry plugins;

        protected AutoRegisteringStaticResourceRegistry(PluginRegistry plugins) {
            super(plugins);
            this.plugins = plugins;
        }

        @Override
        public void register(PreparedResources preparedResources) {
            if (!plugins.containsIdentity(preparedResources.owner())) {
                plugins.register(preparedResources.owner());
            }
            super.register(preparedResources);
        }
    }

    protected static DownloadHarness downloadHarness() {
        return downloadHarness(new PluginRegistry(List.of()));
    }

    protected static LifecycleHarness bootLifecycleHarness(PluginRegistry.RegisteredPlugin registered) {
        PluginRegistry plugins = new PluginRegistry(List.of());
        plugins.register(registered);
        RouteAccessRegistry route = new RouteAccessRegistry(plugins);
        StaticResourceRegistry staticResources = new StaticResourceRegistry(plugins);
        WebI18nBundleRegistry i18n = new WebI18nBundleRegistry(plugins);
        NavigationRegistry navigation = new NavigationRegistry(plugins);
        WebUiSlotRegistry uiSlots = new WebUiSlotRegistry(plugins);
        UserscriptRegistry userscripts = new UserscriptRegistry(plugins);
        ScriptRegistry scripts = new ScriptRegistry(TestI18nBeans.appMessages(), userscripts);
        StartupRouteRegistry startup = new StartupRouteRegistry(plugins);
        LandingRegistry landing = new LandingRegistry(plugins);
        PageSectionRegistry pageSections = new PageSectionRegistry(plugins);
        DrilldownRegistry drilldowns = new DrilldownRegistry(plugins);
        DownloadExtensionRegistry downloads = new DownloadExtensionRegistry(
                plugins, staticResources, new PluginOwnedWebAssetValidator(staticResources));
        PluginWebContributionRegistrar registrar = new PluginWebContributionRegistrar(
                route, staticResources, i18n, navigation, uiSlots, userscripts, scripts,
                plugins, downloads, new PluginRequestLeaseRegistry(),
                startup, landing, pageSections, drilldowns);
        return new LifecycleHarness(startup, landing, pageSections, drilldowns, registrar);
    }

    protected static DownloadHarness downloadHarness(PluginRegistry plugins) {
        return downloadHarness(plugins, new RouteAccessRegistry(plugins));
    }

    protected static DownloadHarness downloadHarness(
            PluginRegistry plugins,
            RouteAccessRegistry route) {
        StaticResourceRegistry staticRes = new StaticResourceRegistry(plugins);
        WebI18nBundleRegistry i18n = new WebI18nBundleRegistry(plugins);
        NavigationRegistry nav = new NavigationRegistry(plugins);
        WebUiSlotRegistry uiSlot = new WebUiSlotRegistry(plugins);
        UserscriptRegistry userscripts = new UserscriptRegistry(plugins);
        ScriptRegistry scripts = new ScriptRegistry(TestI18nBeans.appMessages(), userscripts);
        PluginOwnedWebAssetValidator assetValidator = new PluginOwnedWebAssetValidator(staticRes);
        DownloadExtensionRegistry downloads = new DownloadExtensionRegistry(
                plugins, staticRes, assetValidator);
        PluginRequestLeaseRegistry requestLeases = new PluginRequestLeaseRegistry();
        PluginWebContributionRegistrar registrar = new PluginWebContributionRegistrar(
                route, staticRes, i18n, nav, uiSlot, userscripts, scripts,
                plugins, downloads, requestLeases);
        return new DownloadHarness(plugins, route, staticRes, downloads, requestLeases, registrar);
    }

    protected static PluginRegistry.RegisteredPlugin external(PixivFeaturePlugin plugin) {
        return new PluginRegistry.RegisteredPlugin(plugin, PluginSource.EXTERNAL, CL);
    }

    protected static PluginRegistry.RegisteredPlugin external(PixivFeaturePlugin plugin,
                                                             String packageId,
                                                             long generation) {
        return new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, CL, packageId, generation);
    }

    /** 十类注册中心快照 + 脚本层的稳定投影，用于「注册 → 注销 → 再注册」一致性与「内置不变」断言。 */
    protected record Fingerprint(List<String> routes, List<String> staticPrefixes, List<String> namespaces,
                              List<String> navIds, List<String> startupPaths, List<String> landingIds,
                              List<String> pageSectionIds, List<String> drilldownIds, List<String> uiSlotIds,
                              List<String> userscriptDeclarations, List<String> scriptIds) {

        static Fingerprint of(Harness h) {
            return new Fingerprint(
                    h.route.routes().stream().map(r -> r.route().pathPattern()).sorted().toList(),
                    h.staticRes.resources().stream().map(s -> s.contribution().publicPathPrefix()).sorted().toList(),
                    h.i18n.bundles().stream().map(b -> b.contribution().namespace()).sorted().toList(),
                    h.nav.navigation().stream().map(n -> n.navigation().id()).sorted().toList(),
                    h.startup.startupRoutes().stream().map(s -> s.route().path()).sorted().toList(),
                    h.landing.landings().stream().map(l -> l.landing().id()).sorted().toList(),
                    h.pageSections.sections().stream().map(s -> s.section().id()).sorted().toList(),
                    h.drilldowns.drilldowns().stream().map(d -> d.drilldown().id()).sorted().toList(),
                    h.uiSlot.slots().stream().map(s -> s.slot().slotId()).sorted().toList(),
                    h.userscripts.userscripts().stream()
                            .map(u -> u.contribution().id() + "|" + u.contribution().classpathResource())
                            .sorted()
                            .toList(),
                    h.scripts.scripts().stream().map(s -> s.id()).sorted().toList());
        }

        static Fingerprint empty() {
            return new Fingerprint(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of());
        }
    }

    /** 覆盖四类此前只在启动期聚合、现在必须随外置 serving 可逆接入的 web 贡献。 */
    protected record LifecycleWebPlugin(String id, String sectionId) implements PixivFeaturePlugin {
        @Override public String displayName() { return "lifecycle.plugin.name"; }
        @Override public String description() { return "lifecycle.plugin.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }

        @Override
        public List<StartupRouteContribution> startupRoutes() {
            return List.of(new StartupRouteContribution(
                    "/" + id + ".html", 10, Set.of(StartupRouteContext.SOLO)));
        }

        @Override
        public List<LandingContribution> landings() {
            return List.of(new LandingContribution(
                    id + ".landing", Audience.INVITED_GUEST, "/" + id + ".html", 10));
        }

        @Override
        public List<PageSectionContribution> pageSections() {
            return List.of(lifecycleSection(sectionId));
        }

        @Override
        public List<DrilldownContribution> drilldowns() {
            return List.of(new DrilldownContribution(
                    id + ".drilldown", "lifecycle.drilldown", "/" + id + ".html?work={id}",
                    AccessPolicy.ADMIN, 10));
        }
    }

    /** 外置功能插件：贡献 route / static / i18n / navigation / userscript 各非空，仅用 plugin.api 契约类型。 */
    protected static final class WebDemoPlugin implements PixivFeaturePlugin {
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
                    "classpath:/test-userscripts/", "/web-demo/"));
        }

        @Override
        public List<I18nContribution> i18n() {
            return List.of(new I18nContribution("web-demo", "i18n.web.common"));
        }

        @Override
        public List<NavigationContribution> navigation() {
            return List.of(new NavigationContribution("web-demo-nav", "app.top", "ns", "web-demo.nav.label",
                    "/web-demo.html", null, AccessPolicy.ADMIN, 500));
        }

        @Override
        public List<WebUiSlotContribution> uiSlots() {
            return List.of(new WebUiSlotContribution(
                    "web-demo.slot", "demo-anchor", "/web-demo/slot.js", 10));
        }

        @Override
        public List<UserscriptContribution> userscripts() {
            return List.of(new UserscriptContribution(
                    "sample-plugin", "classpath:/test-userscripts/sample-plugin.user.js"));
        }
    }

    /** 只贡献一个下载类型，并可选贡献普通路由，用于验证统一注册事务与精确代际撤回。 */
    protected record DownloadOnlyPlugin(
            String id,
            String type,
            List<WebRouteContribution> routes
    ) implements PixivFeaturePlugin {
        @Override public String displayName() { return "download.plugin.name"; }
        @Override public String description() { return "download.plugin.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<WebRouteContribution> routes() { return routes; }

        @Override
        public List<DownloadTypeDescriptor> downloadTypes() {
            return List.of(TestDownloadTypeDescriptors.create(
                    type, "download", "kind." + type, 10, moduleUrl()));
        }

        @Override
        public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution(
                    "classpath:/test-download/", publicPrefix()));
        }

        protected String publicPrefix() {
            return "/" + id + "-download/";
        }

        protected String moduleUrl() {
            return publicPrefix() + "module.js";
        }
    }

    /** 启动期外置 route getter 只允许 Web registrar 读取一次。 */
    protected static final class BootRouteReadOncePlugin implements PixivFeaturePlugin {
        protected final AtomicInteger routeReads = new AtomicInteger();

        @Override public String id() { return "boot-external"; }
        @Override public String displayName() { return "boot-external.name"; }
        @Override public String description() { return "boot-external.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }

        @Override
        public List<WebRouteContribution> routes() {
            if (routeReads.incrementAndGet() != 1) {
                throw new AssertionError("external boot routes were read more than once");
            }
            return List.of(WebRouteContribution.admin("/boot-external/**"));
        }

        int routeReads() {
            return routeReads.get();
        }
    }

    /** 前几个 getter 有有效贡献、后续 getter 抛 Error，用于验证插件回调安全边界。 */
    protected static final class LateGetterErrorPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "late-getter-error"; }
        @Override public String displayName() { return "late-getter-error.name"; }
        @Override public String description() { return "late-getter-error.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<WebRouteContribution> routes() {
            return List.of(WebRouteContribution.admin("/late-getter-error/**"));
        }
        @Override public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution(
                    "classpath:/test-download/", "/late-getter-error/"));
        }
        @Override public List<I18nContribution> i18n() {
            throw new AssertionError("plugin-controlled error text");
        }
    }

    /** 下载 getter 在普通 web 声明之后抛 Error，验证统一注册事务不泄漏半足迹。 */
    protected static final class DownloadGetterErrorPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "download-getter-error"; }
        @Override public String displayName() { return "download-getter-error.name"; }
        @Override public String description() { return "download-getter-error.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<WebRouteContribution> routes() {
            return List.of(WebRouteContribution.admin("/download-getter-error/**"));
        }
        @Override public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution(
                    "classpath:/test-download/", "/download-getter-error/"));
        }
        @Override public List<DownloadTypeDescriptor> downloadTypes() {
            throw new AssertionError("plugin-controlled download error text");
        }
    }

    /** 第二次静态 getter 返回错误位置；正确实现只读取第一次并复用于 serving 与模块校验。 */
    protected static final class FlakyStaticDownloadPlugin implements PixivFeaturePlugin {
        protected final AtomicInteger staticReads = new AtomicInteger();

        @Override public String id() { return "snapshot-owner"; }
        @Override public String displayName() { return "snapshot-owner.name"; }
        @Override public String description() { return "snapshot-owner.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }

        @Override
        public List<StaticResourceContribution> staticResources() {
            String location = staticReads.incrementAndGet() == 1
                    ? "classpath:/test-download/"
                    : "classpath:/missing-second-read/";
            return List.of(new StaticResourceContribution(location, "/snapshot/"));
        }

        @Override
        public List<DownloadTypeDescriptor> downloadTypes() {
            return List.of(TestDownloadTypeDescriptors.create(
                    "snapshot-type", "download", "kind.snapshot", 10,
                    "/snapshot/module.js"));
        }

        int staticReads() {
            return staticReads.get();
        }
    }

    /** 只有普通 route、下载扩展为空，用于覆盖统一 web 事务的空 bundle 最终身份复核。 */
    protected static final class EmptyDownloadWebPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "empty-download-web"; }
        @Override public String displayName() { return "empty-download-web.name"; }
        @Override public String description() { return "empty-download-web.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<WebRouteContribution> routes() {
            return List.of(WebRouteContribution.admin("/empty-download-web/**"));
        }
    }

    /** 在 getter 阶段暂停，用于证明准备不持有 web/plugin registry 锁。 */
    protected record BlockingEmptyDownloadWebPlugin(
            CountDownLatch entered,
            CountDownLatch release
    ) implements PixivFeaturePlugin {
        @Override public String id() { return "empty-download-web"; }
        @Override public String displayName() { return "empty-download-web.name"; }
        @Override public String description() { return "empty-download-web.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }

        @Override
        public List<WebRouteContribution> routes() {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release plugin getter");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("plugin getter interrupted");
            }
            return List.of(WebRouteContribution.admin("/empty-download-web/**"));
        }
    }

    protected record BlockingRoutePlugin(
            String id,
            CountDownLatch entered,
            CountDownLatch release
    ) implements PixivFeaturePlugin {
        @Override public String displayName() { return id + ".name"; }
        @Override public String description() { return id + ".summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }

        @Override
        public List<WebRouteContribution> routes() {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release plugin getter");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("plugin getter interrupted");
            }
            return List.of(WebRouteContribution.admin("/" + id + "/**"));
        }
    }

    protected static final class FailingRouteAccessRegistry extends RouteAccessRegistry {
        protected boolean failBeforeUnregister;

        protected FailingRouteAccessRegistry(PluginRegistry plugins) {
            super(plugins);
        }

        @Override
        public void unregister(String pluginId) {
            if (failBeforeUnregister) {
                throw new AssertionError("route cleanup failed before snapshot mutation");
            }
            super.unregister(pluginId);
        }

        @Override
        public void unregister(PluginRequestOwner owner) {
            if (failBeforeUnregister) {
                throw new AssertionError("route cleanup failed before snapshot mutation");
            }
            super.unregister(owner);
        }
    }

    /** 观测启动期外置路由从未走 owner-null 发布，只允许 exact owner 重载。 */
    protected static final class OwnerTrackingRouteAccessRegistry extends RouteAccessRegistry {
        protected int ownerlessExternalRegistrations;
        protected int exactExternalRegistrations;

        protected OwnerTrackingRouteAccessRegistry(PluginRegistry plugins) {
            super(plugins);
        }

        @Override
        public void register(String pluginId, List<WebRouteContribution> routes) {
            if ("boot-external".equals(pluginId)) {
                ownerlessExternalRegistrations++;
            }
            super.register(pluginId, routes);
        }

        @Override
        public void register(PluginRequestOwner owner, List<WebRouteContribution> routes) {
            if ("boot-external".equals(owner.pluginId())) {
                exactExternalRegistrations++;
            }
            super.register(owner, routes);
        }

        int ownerlessExternalRegistrations() {
            return ownerlessExternalRegistrations;
        }

        int exactExternalRegistrations() {
            return exactExternalRegistrations;
        }
    }

    /**
     * 另一个外置插件：route / static 唯一，但 i18n namespace 与 {@link WebDemoPlugin} 冲突（同 "web-demo"）。
     * 用于验证 register 在 i18n 步骤 fail-fast 时，已接入的 route / static 被原子回滚。
     */
    protected static final class ConflictingI18nPlugin implements PixivFeaturePlugin {
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
                    "classpath:/test-userscripts/", "/web-demo-2/"));
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
    protected static Optional<PluginRequestLease> acquire(
            PluginRequestLeaseRegistry registry,
            top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestOwner owner) {
        Optional<PluginRequestLease> prepared = registry.prepareLease(owner);
        if (prepared.isEmpty()) {
            return Optional.empty();
        }
        PluginRequestLease lease = prepared.orElseThrow();
        boolean active = false;
        try {
            active = registry.activate(lease);
            return active ? Optional.of(lease) : Optional.empty();
        } finally {
            if (!active) {
                lease.close();
            }
        }
    }

    protected static final class ConflictingUiSlotPlugin implements PixivFeaturePlugin {
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
                    "classpath:/test-userscripts/", "/web-demo-3/"));
        }

        @Override
        public List<NavigationContribution> navigation() {
            return List.of(new NavigationContribution("web-demo-3-nav", "app.top", "ns", "web-demo-3.nav.label",
                    "/web-demo-3.html", null, AccessPolicy.ADMIN, 500));
        }

        @Override
        public List<WebUiSlotContribution> uiSlots() {
            return List.of(new WebUiSlotContribution(
                    "web-demo.slot", "demo-anchor-3", "/web-demo-3/slot.js", 10));
        }
    }
}
