package top.sywyar.pixivdownload.plugin.web.registration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
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

@DisplayName("插件 Web 贡献：原子 publication 与可逆注册")
class PluginWebContributionRegistrarTest extends PluginWebContributionRegistrarTestSupport {

    @Test
    @DisplayName("register 把十类贡献接入各注册中心（classloader-aware），ScriptRegistry 刷新出脚本")
    void registerExposesAllTenContributions() {
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
        assertThat(bundle.load(java.util.Locale.US)).isNotEmpty();

        assertThat(h.nav.navigation()).anyMatch(n -> n.pluginId().equals("web-demo"));

        WebUiSlotRegistry.RegisteredUiSlot uiSlot = h.uiSlot.slots().stream()
                .filter(s -> s.pluginId().equals("web-demo")).findFirst().orElseThrow();
        assertThat(uiSlot.slot().target()).isEqualTo("demo-anchor");
        assertThat(uiSlot.slot().moduleUrl()).isEqualTo("/web-demo/slot.js");

        UserscriptRegistry.RegisteredUserscript userscript = h.userscripts.userscripts().stream()
                .filter(u -> u.pluginId().equals("web-demo")).findFirst().orElseThrow();
        assertThat(userscript.classLoader()).isSameAs(CL);

        // userscript 声明接入后 ScriptRegistry 经声明方 classloader 物化本插件脚本（已注册声明被刷新）。
        assertThat(h.scripts.scripts()).anyMatch(s -> s.id().equals("sample-plugin"));
    }

    @Test
    @DisplayName("unregister 后十类快照与脚本层均无残留；路由「未声明」即 AuthFilter 404")
    void unregisterLeavesNoResidueAndStaticBecomesUndeclared() {
        Harness h = emptyHarness();
        PluginRegistry.RegisteredPlugin registered = external(new WebDemoPlugin());
        PluginWebContributionHandle handle = h.registrar.register(registered);
        // 前置：静态资源 URL 已被路由声明（AuthFilter 放行后才到 ResourceHandler）
        assertThat(h.route.isDeclared("/web-demo/page.css", HttpMethod.GET)).isTrue();

        h.registrar.unregister(handle);

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
        assertThat(h.scripts.scripts()).noneMatch(s -> s.id().equals("sample-plugin"));
    }

    @Test
    @DisplayName("注册 → 注销 → 再注册后各注册中心快照与首次一致")
    void registerUnregisterReRegisterIsConsistent() {
        Harness h = emptyHarness();
        PixivFeaturePlugin plugin = new WebDemoPlugin();
        PluginRegistry.RegisteredPlugin firstRegistration = external(plugin);

        PluginWebContributionHandle firstHandle = h.registrar.register(firstRegistration);
        Fingerprint first = Fingerprint.of(h);

        h.registrar.unregister(firstHandle);
        assertThat(Fingerprint.of(h)).isEqualTo(Fingerprint.empty());

        h.registrar.register(firstRegistration);
        assertThat(Fingerprint.of(h)).isEqualTo(first);
    }

    @Test
    @DisplayName("启动落点、业务落点、页面区块和下钻随外置 serving 撤回并可重新注册")
    void lifecycleRegistriesWithdrawAndReRegisterWithoutResidue() {
        PluginRegistry.RegisteredPlugin registered = external(
                new LifecycleWebPlugin("lifecycle-web", "lifecycle-web.section"), "lifecycle-web", 1L);
        LifecycleHarness h = bootLifecycleHarness(registered);

        PluginWebContributionHandle first = h.registrar.currentHandle(registered).orElseThrow();

        assertLifecycleContributions(h, "lifecycle-web", true);

        h.registrar.unregister(first);

        assertLifecycleContributions(h, "lifecycle-web", false);

        PluginWebContributionHandle second = h.registrar.register(registered);

        assertThat(second).isNotSameAs(first);
        assertLifecycleContributions(h, "lifecycle-web", true);

        h.registrar.unregister(second);
        assertLifecycleContributions(h, "lifecycle-web", false);
    }

    @Test
    @DisplayName("页面区块冲突会回滚此前接入的启动落点与业务落点")
    void lifecycleRegistryConflictRollsBackEarlierContributions() {
        Harness h = emptyHarness();
        h.pageSections.register("existing", List.of(lifecycleSection("shared.section")));

        assertThatThrownBy(() -> h.registrar.register(external(
                new LifecycleWebPlugin("lifecycle-failure", "shared.section"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared.section");

        assertLifecycleContributions(h, "lifecycle-failure", false);
        assertThat(h.pageSections.sections())
                .singleElement()
                .satisfies(section -> {
                    assertThat(section.pluginId()).isEqualTo("existing");
                    assertThat(section.section().id()).isEqualTo("shared.section");
                });
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
        int builtInScripts = h.scripts.scripts().size();
        PluginRegistry.RegisteredPlugin registered = external(new WebDemoPlugin());

        PluginWebContributionHandle handle = h.registrar.register(registered);
        h.registrar.unregister(handle);

        assertThat(Fingerprint.of(h)).isEqualTo(builtIn);
        assertThat(h.scripts.scripts()).hasSize(builtInScripts);
    }
}
