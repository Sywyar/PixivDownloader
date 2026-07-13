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
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestGenerationDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLease;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLeaseRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestOwner;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebAssetValidator;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionHandle;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionRegistrar;
import top.sywyar.pixivdownload.scripts.ScriptRegistry;
import top.sywyar.pixivdownload.scripts.UserscriptRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
        assertThat(bundle.load(java.util.Locale.US)).isNotEmpty();

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
        assertThat(h.scripts.getScripts()).noneMatch(s -> s.id().equals("sample-plugin"));
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
        PluginRegistry.RegisteredPlugin registered = external(new WebDemoPlugin());

        PluginWebContributionHandle handle = h.registrar.register(registered);
        h.registrar.unregister(handle);

        assertThat(Fingerprint.of(h)).isEqualTo(builtIn);
        assertThat(h.scripts.getScripts()).hasSize(builtInScripts);
    }

    @Test
    @DisplayName("同一注册身份重新 serving 后旧句柄不能撤回新 publication")
    void staleServingHandleCannotWithdrawNewDownloadPublication() {
        DownloadHarness h = downloadHarness();
        PluginRegistry.RegisteredPlugin registered = external(
                new DownloadOnlyPlugin("download-owner", "owned-type", List.of()),
                "download-owner", 1L);
        h.plugins.register(registered);
        PluginWebContributionHandle first = h.registrar.register(registered);
        long firstPublication = h.downloads.snapshot().queueTypes().get(0).publicationId();

        h.registrar.unregister(first);
        PluginWebContributionHandle second = h.registrar.register(registered);
        DownloadExtensionRegistry.Snapshot secondSnapshot = h.downloads.snapshot();

        assertThat(secondSnapshot.queueTypes()).singleElement().satisfies(item -> {
            assertThat(item.owner().generation()).isEqualTo(1L);
            assertThat(item.publicationId()).isGreaterThan(firstPublication);
        });
        assertThat(second).isNotSameAs(first);
        assertThat(h.registrar.unregister(first)).isFalse();
        assertThat(h.downloads.snapshot()).isSameAs(secondSnapshot);

        h.registrar.unregister(second);
        assertThat(h.downloads.snapshot().queueTypes()).isEmpty();
    }

    @Test
    @DisplayName("启动期已接入插件可以取得当前 serving 句柄")
    void bootRegistrationExposesCurrentHandle() {
        PluginRegistry plugins = new PluginRegistry(List.of(
                new DownloadOnlyPlugin("boot-owner", "boot-type", List.of())));
        DownloadHarness h = downloadHarness(plugins);
        PluginRegistry.RegisteredPlugin registered = plugins.registeredPlugins().get(0);

        PluginWebContributionHandle handle = h.registrar.currentHandle(registered).orElseThrow();
        assertThat(handle.pluginId()).isEqualTo("boot-owner");
        assertThat(h.registrar.unregister(handle)).isTrue();
        assertThat(h.downloads.snapshot().queueTypes()).isEmpty();
    }

    @Test
    @DisplayName("启动期外置路由绑定 Web 句柄的精确 owner 并发布请求准入")
    void bootExternalRoutesBindExactRequestOwner() {
        PluginRegistry plugins = new PluginRegistry(List.of());
        BootRouteReadOncePlugin plugin = new BootRouteReadOncePlugin();
        PluginRegistry.RegisteredPlugin registered = external(
                plugin,
                "boot-external", 6L);
        plugins.register(registered);
        OwnerTrackingRouteAccessRegistry routes = new OwnerTrackingRouteAccessRegistry(plugins);

        DownloadHarness h = downloadHarness(plugins, routes);
        PluginWebContributionHandle handle = h.registrar.currentHandle(registered).orElseThrow();

        assertThat(plugin.routeReads()).isOne();
        assertThat(routes.ownerlessExternalRegistrations()).isZero();
        assertThat(routes.exactExternalRegistrations()).isOne();
        assertThat(h.requestLeases.currentOwner("boot-external"))
                .contains(handle.requestOwner());
        assertThat(h.route.routes())
                .filteredOn(route -> route.pluginId().equals("boot-external"))
                .allMatch(route -> handle.requestOwner().equals(route.requestOwner()));
        try (PluginRequestLease lease = acquire(h.requestLeases, handle.requestOwner()).orElseThrow()) {
            assertThat(lease.owner()).isEqualTo(handle.requestOwner());
        }
        assertThat(h.registrar.unregister(handle)).isTrue();
    }

    @Test
    @DisplayName("启动期下载 publication 读取失败发生在请求与路由发布前且零残留")
    void bootPublicationLookupFailureLeavesNoRequestOrRouteFootprint() {
        PluginRegistry plugins = new PluginRegistry(List.of());
        BootRouteReadOncePlugin plugin = new BootRouteReadOncePlugin();
        PluginRegistry.RegisteredPlugin registered = external(
                plugin, "boot-external", 7L);
        plugins.register(registered);
        RouteAccessRegistry routes = new RouteAccessRegistry(plugins);
        StaticResourceRegistry statics = mock(StaticResourceRegistry.class);
        WebI18nBundleRegistry i18n = mock(WebI18nBundleRegistry.class);
        NavigationRegistry navigation = mock(NavigationRegistry.class);
        WebUiSlotRegistry slots = mock(WebUiSlotRegistry.class);
        UserscriptRegistry userscripts = mock(UserscriptRegistry.class);
        ScriptRegistry scripts = mock(ScriptRegistry.class);
        DownloadExtensionRegistry downloads = mock(DownloadExtensionRegistry.class);
        PluginRequestLeaseRegistry requestLeases = new PluginRequestLeaseRegistry();
        when(downloads.currentPublication(registered))
                .thenThrow(new IllegalStateException("boot publication lookup failed"));

        assertThatThrownBy(() -> new PluginWebContributionRegistrar(
                routes, statics, i18n, navigation, slots, userscripts, scripts,
                plugins, downloads, requestLeases))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boot publication lookup failed");

        assertThat(plugin.routeReads()).isZero();
        assertThat(routes.routes()).noneMatch(route -> route.pluginId().equals("boot-external"));
        assertThat(requestLeases.currentOwner("boot-external")).isEmpty();
    }

    @Test
    @DisplayName("运行期提交复用 prepared 时分配的 owner 并随路由精确发布")
    void runtimeCommitUsesPreparedExactRequestOwner() {
        DownloadHarness h = downloadHarness();
        PluginRegistry.RegisteredPlugin registered = external(
                new DownloadOnlyPlugin(
                        "runtime-owner", "runtime-owner-type",
                        List.of(WebRouteContribution.admin("/runtime-owner/**"))),
                "runtime-owner", 8L);
        h.plugins.register(registered);
        PluginWebContributionRegistrar.PreparedWebContribution prepared = h.registrar.prepare(registered);
        PluginRequestOwner preparedOwner = prepared.requestOwner();

        PluginWebContributionHandle handle = h.registrar.commit(prepared);

        assertThat(handle.requestOwner()).isEqualTo(preparedOwner);
        assertThat(h.requestLeases.currentOwner("runtime-owner")).contains(preparedOwner);
        assertThat(h.route.resolve("/runtime-owner/status", HttpMethod.GET)).get()
                .extracting(RouteAccessRegistry.RegisteredRoute::requestOwner)
                .isEqualTo(preparedOwner);
        assertThat(h.registrar.unregister(handle)).isTrue();
    }

    @Test
    @DisplayName("请求 drain 是路由与子资源拆除前置，仍有租约时保留当前 serving 供重试")
    void activeRequestDrainPreventsWebTeardownUntilRetry() {
        DownloadHarness h = downloadHarness();
        PluginRegistry.RegisteredPlugin registered = external(
                new DownloadOnlyPlugin(
                        "draining-owner", "draining-owner-type",
                        List.of(WebRouteContribution.admin("/draining-owner/**"))),
                "draining-owner", 9L);
        h.plugins.register(registered);
        PluginWebContributionHandle handle = h.registrar.register(registered);
        PluginRequestLease lease = acquire(h.requestLeases, handle.requestOwner()).orElseThrow();

        PluginRequestGenerationDrain drain = h.registrar.withdrawRequests(handle).orElseThrow();

        assertThat(drain.activeLeaseCount()).isOne();
        assertThat(acquire(h.requestLeases, handle.requestOwner())).isEmpty();
        assertThatThrownBy(() -> h.registrar.unregister(handle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to fully unregister web contributions");
        assertThat(h.route.resolve("/draining-owner/status", HttpMethod.GET)).isPresent();
        assertThat(h.registrar.currentHandle(registered)).containsSame(handle);

        lease.close();
        assertThat(drain.isDrained()).isTrue();
        assertThat(h.registrar.unregister(handle)).isTrue();
        assertThat(h.route.resolve("/draining-owner/status", HttpMethod.GET)).isEmpty();
    }

    @Test
    @DisplayName("并发 prepare 在短宿主锁内分配互不重复的 servingId")
    void concurrentPrepareAllocatesUniqueServingIds() throws Exception {
        Harness h = emptyHarness();
        PluginRegistry.RegisteredPlugin registered = external(
                new EmptyDownloadWebPlugin(), "empty-download-web", 12L);
        int taskCount = 16;
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        List<Future<PluginRequestOwner>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < taskCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting for concurrent prepare start");
                    }
                    return h.registrar.prepare(registered).requestOwner();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<PluginRequestOwner> owners = new ArrayList<>();
            for (Future<PluginRequestOwner> future : futures) {
                owners.add(future.get(5, TimeUnit.SECONDS));
            }

            assertThat(new HashSet<>(owners)).hasSize(taskCount);
            assertThat(owners).allSatisfy(owner -> {
                assertThat(owner.pluginId()).isEqualTo("empty-download-web");
                assertThat(owner.generation()).isEqualTo(12L);
            });
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("下载类型冲突时已接入的普通 web 贡献回滚且原快照 revision 不变")
    void downloadConflictRollsBackOrdinaryWebContributions() {
        DownloadHarness h = downloadHarness();
        PluginRegistry.RegisteredPlugin owner = external(
                new DownloadOnlyPlugin("download-owner-a", "shared-type", List.of()),
                "download-owner-a", 1L);
        h.plugins.register(owner);
        h.registrar.register(owner);
        DownloadExtensionRegistry.Snapshot before = h.downloads.snapshot();

        PluginRegistry.RegisteredPlugin contender = external(
                new DownloadOnlyPlugin(
                        "download-owner-b",
                        "shared-type",
                        List.of(WebRouteContribution.admin("/download-owner-b/**"))),
                "download-owner-b", 1L);
        h.plugins.register(contender);

        assertThatThrownBy(() -> h.registrar.register(contender))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate download queue type");
        assertThat(h.route.routes()).noneMatch(route -> route.pluginId().equals("download-owner-b"));
        assertThat(h.downloads.snapshot()).isSameAs(before);
    }

    @Test
    @DisplayName("后续插件 getter 抛 AssertionError 时转受控无 cause 异常且不产生半足迹")
    void pluginGetterErrorLeavesNoPartialWebFootprint() {
        Harness h = emptyHarness();
        PluginRegistry.RegisteredPlugin registered = external(new LateGetterErrorPlugin());

        assertThatThrownBy(() -> h.registrar.register(registered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failureType=java.lang.AssertionError")
                .hasNoCause();

        assertThat(Fingerprint.of(h)).isEqualTo(Fingerprint.empty());
        assertThat(h.registrar.currentHandle(registered)).isEmpty();
    }

    @Test
    @DisplayName("下载贡献 getter 抛 AssertionError 时普通 web 足迹全部回滚且下载快照不变")
    void downloadGetterErrorRollsBackAllPreparedWebFootprints() {
        DownloadHarness h = downloadHarness();
        PluginRegistry.RegisteredPlugin registered = external(
                new DownloadGetterErrorPlugin(), "download-getter-error", 1L);
        h.plugins.register(registered);
        DownloadExtensionRegistry.Snapshot before = h.downloads.snapshot();

        assertThatThrownBy(() -> h.registrar.register(registered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("queueTypes")
                .hasMessageContaining("failureType=java.lang.AssertionError")
                .hasNoCause();

        assertThat(h.route.routes()).noneMatch(route -> route.pluginId().equals("download-getter-error"));
        assertThat(h.staticResources.resources())
                .noneMatch(resource -> resource.pluginId().equals("download-getter-error"));
        assertThat(h.downloads.snapshot()).isSameAs(before);
        assertThat(h.registrar.currentHandle(registered)).isEmpty();
    }

    @Test
    @DisplayName("静态资源 getter 只读取一次，serving 与下载模块校验复用同一快照")
    void staticContributionGetterIsSnapshottedOnceForServingAndValidation() {
        DownloadHarness h = downloadHarness();
        FlakyStaticDownloadPlugin plugin = new FlakyStaticDownloadPlugin();
        PluginRegistry.RegisteredPlugin registered = external(plugin, "snapshot-owner", 1L);
        h.plugins.register(registered);

        PluginWebContributionHandle handle = h.registrar.register(registered);

        assertThat(plugin.staticReads()).isEqualTo(1);
        assertThat(h.staticResources.resources()).singleElement().satisfies(resource ->
                assertThat(resource.contribution().classpathLocation()).isEqualTo("classpath:/test-download/"));
        assertThat(h.downloads.snapshot().queueTypes()).singleElement().satisfies(queue ->
                assertThat(queue.queueType().moduleUrl()).isEqualTo("/snapshot/module.js"));
        assertThat(h.registrar.unregister(handle)).isTrue();
    }

    @Test
    @DisplayName("unregister 遇到非致命 Error 保留 current 句柄，重试只继续未完成清理")
    void unregisterContinuesBestEffortAfterRegistryError() {
        RouteAccessRegistry routes = mock(RouteAccessRegistry.class);
        StaticResourceRegistry statics = mock(StaticResourceRegistry.class);
        WebI18nBundleRegistry i18n = mock(WebI18nBundleRegistry.class);
        NavigationRegistry navigation = mock(NavigationRegistry.class);
        WebUiSlotRegistry slots = mock(WebUiSlotRegistry.class);
        UserscriptRegistry userscripts = mock(UserscriptRegistry.class);
        ScriptRegistry scripts = mock(ScriptRegistry.class);
        PluginWebContributionRegistrar registrar = new PluginWebContributionRegistrar(
                routes, statics, i18n, navigation, slots, userscripts, scripts);
        PluginRegistry.RegisteredPlugin registered = external(new WebDemoPlugin());
        PluginWebContributionHandle handle = registrar.register(registered);
        clearInvocations(routes, statics, i18n, navigation, slots, userscripts, scripts);
        doThrow(new AssertionError("route cleanup failed"))
                .doNothing()
                .when(routes).unregister(handle.requestOwner());

        assertThatThrownBy(() -> registrar.unregister(handle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failureType=java.lang.AssertionError")
                .hasNoCause();

        verify(statics).unregister("web-demo");
        verify(i18n).unregister("web-demo");
        verify(navigation).unregister("web-demo");
        verify(slots).unregister("web-demo");
        verify(userscripts).unregister("web-demo");
        verify(scripts).refresh();
        assertThat(registrar.currentHandle(registered)).containsSame(handle);

        assertThat(registrar.unregister(handle)).isTrue();
        assertThat(registrar.currentHandle(registered)).isEmpty();
        verify(routes, times(2)).unregister(handle.requestOwner());
        verify(statics, times(1)).unregister("web-demo");
        verify(i18n, times(1)).unregister("web-demo");
        verify(navigation, times(1)).unregister("web-demo");
        verify(slots, times(1)).unregister("web-demo");
        verify(userscripts, times(1)).unregister("web-demo");
        verify(scripts, times(1)).refresh();
    }

    @Test
    @DisplayName("插件 getter 阻塞时不占用 registrar 锁，currentHandle 与旧 serving 注销可并发完成")
    void blockingPluginGetterDoesNotBlockCurrentHandleOrUnregister() throws Exception {
        Harness h = emptyHarness();
        PluginRegistry.RegisteredPlugin current = external(new WebDemoPlugin());
        PluginWebContributionHandle currentHandle = h.registrar.register(current);
        CountDownLatch getterEntered = new CountDownLatch(1);
        CountDownLatch releaseGetter = new CountDownLatch(1);
        PluginRegistry.RegisteredPlugin slow = external(
                new BlockingRoutePlugin("slow-getter", getterEntered, releaseGetter));
        AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
        Thread registration = new Thread(() -> {
            try {
                h.registrar.register(slow);
            } catch (Throwable failure) {
                registrationFailure.set(failure);
            }
        }, "slow-plugin-getter");
        registration.start();
        assertThat(getterEntered.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch observerDone = new CountDownLatch(1);
        AtomicReference<Throwable> observerFailure = new AtomicReference<>();
        Thread observer = new Thread(() -> {
            try {
                assertThat(h.registrar.currentHandle(current)).containsSame(currentHandle);
                assertThat(h.registrar.unregister(currentHandle)).isTrue();
            } catch (Throwable failure) {
                observerFailure.set(failure);
            } finally {
                observerDone.countDown();
            }
        }, "web-handle-observer");
        observer.start();
        try {
            assertThat(observerDone.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(observerFailure.get()).isNull();
        } finally {
            releaseGetter.countDown();
        }
        registration.join(5000);

        assertThat(registration.isAlive()).isFalse();
        assertThat(registrationFailure.get()).isNull();
        PluginWebContributionHandle slowHandle = h.registrar.currentHandle(slow).orElseThrow();
        assertThat(h.registrar.unregister(slowHandle)).isTrue();
    }

    @Test
    @DisplayName("正向注册失败且回滚在删除前报错时保留 provisional current 句柄供二次清零")
    void failedRegistrationRollbackRemainsRetryable() {
        PluginRegistry plugins = new PluginRegistry(List.of());
        FailingRouteAccessRegistry routes = new FailingRouteAccessRegistry(plugins);
        StaticResourceRegistry statics = new AutoRegisteringStaticResourceRegistry(plugins);
        WebI18nBundleRegistry i18n = mock(WebI18nBundleRegistry.class);
        NavigationRegistry navigation = mock(NavigationRegistry.class);
        WebUiSlotRegistry slots = mock(WebUiSlotRegistry.class);
        UserscriptRegistry userscripts = mock(UserscriptRegistry.class);
        ScriptRegistry scripts = mock(ScriptRegistry.class);
        PluginWebContributionRegistrar registrar = new PluginWebContributionRegistrar(
                routes, statics, i18n, navigation, slots, userscripts, scripts);
        PluginRegistry.RegisteredPlugin registered = external(new WebDemoPlugin());
        routes.failBeforeUnregister = true;
        doThrow(new IllegalStateException("i18n publish failed"))
                .when(i18n).register(
                        org.mockito.ArgumentMatchers.eq("web-demo"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyList());

        assertThatThrownBy(() -> registrar.register(registered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to roll back web contribution registration")
                .hasMessageContaining("failureType=java.lang.AssertionError")
                .hasNoCause();

        PluginWebContributionHandle pending = registrar.currentHandle(registered).orElseThrow();
        assertThat(registrar.isCurrent(pending)).isTrue();
        assertThat(routes.routes()).anyMatch(route -> route.pluginId().equals("web-demo"));

        routes.failBeforeUnregister = false;
        assertThat(registrar.unregister(pending)).isTrue();
        assertThat(registrar.isCurrent(pending)).isFalse();
        assertThat(routes.routes()).noneMatch(route -> route.pluginId().equals("web-demo"));
    }

    @Test
    @DisplayName("有状态 registry 首次撤回未删除时 handle 仍 current，重试跳过已成功的下载撤回并可发布新句柄")
    void statefulCleanupFailureRetainsCurrentAndDownloadWithdrawRunsOnce() {
        PluginRegistry plugins = new PluginRegistry(List.of());
        FailingRouteAccessRegistry routes = new FailingRouteAccessRegistry(plugins);
        StaticResourceRegistry statics = new AutoRegisteringStaticResourceRegistry(plugins);
        WebI18nBundleRegistry i18n = new WebI18nBundleRegistry(plugins);
        NavigationRegistry navigation = new NavigationRegistry(plugins);
        WebUiSlotRegistry slots = new WebUiSlotRegistry(plugins);
        UserscriptRegistry userscripts = new UserscriptRegistry(plugins);
        ScriptRegistry scripts = new ScriptRegistry(TestI18nBeans.appMessages(), userscripts);
        DownloadExtensionRegistry downloads = new DownloadExtensionRegistry(
                plugins, statics, new PluginOwnedWebAssetValidator(statics));
        PluginWebContributionRegistrar registrar = new PluginWebContributionRegistrar(
                routes, statics, i18n, navigation, slots, userscripts, scripts, plugins, downloads);
        PluginRegistry.RegisteredPlugin registered = external(
                new DownloadOnlyPlugin(
                        "stateful-owner", "stateful-type",
                        List.of(WebRouteContribution.admin("/stateful-owner/**"))),
                "stateful-owner", 1L);
        plugins.register(registered);
        PluginWebContributionHandle first = registrar.register(registered);
        assertThat(downloads.snapshot().revision()).isEqualTo(1L);
        routes.failBeforeUnregister = true;

        assertThatThrownBy(() -> registrar.unregister(first))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failureType=java.lang.AssertionError")
                .hasNoCause();

        long revisionAfterDownloadWithdraw = downloads.snapshot().revision();
        assertThat(revisionAfterDownloadWithdraw).isEqualTo(2L);
        assertThat(downloads.snapshot().queueTypes()).isEmpty();
        assertThat(routes.routes()).anyMatch(route -> route.pluginId().equals("stateful-owner"));
        assertThat(registrar.isCurrent(first)).isTrue();

        routes.failBeforeUnregister = false;
        assertThat(registrar.unregister(first)).isTrue();
        assertThat(downloads.snapshot().revision()).isEqualTo(revisionAfterDownloadWithdraw);
        assertThat(routes.routes()).noneMatch(route -> route.pluginId().equals("stateful-owner"));
        assertThat(registrar.isCurrent(first)).isFalse();

        PluginWebContributionHandle second = registrar.register(registered);
        assertThat(second).isNotSameAs(first);
        assertThat(second.servingId()).isGreaterThan(first.servingId());
        assertThat(downloads.snapshot().queueTypes()).singleElement().satisfies(queue ->
                assertThat(queue.queueType().type()).isEqualTo("stateful-type"));
        assertThat(registrar.unregister(second)).isTrue();
    }

    @Test
    @DisplayName("统一 web 准备令牌提交尝试后不可重放")
    void preparedWebContributionIsSingleUse() {
        DownloadHarness h = downloadHarness();
        PluginRegistry.RegisteredPlugin registered = external(
                new DownloadOnlyPlugin(
                        "single-use-owner", "single-use-type",
                        List.of(WebRouteContribution.admin("/single-use-owner/**"))),
                "single-use-owner", 1L);
        h.plugins.register(registered);
        PluginWebContributionRegistrar.PreparedWebContribution prepared =
                h.registrar.prepare(registered);

        PluginWebContributionHandle handle = h.registrar.commit(prepared);
        assertThat(h.registrar.unregister(handle)).isTrue();
        assertThatThrownBy(() -> h.registrar.commit(prepared))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already attempted");
        assertThat(h.registrar.currentHandle(registered)).isEmpty();
    }

    @Test
    @DisplayName("锁外 prepare 期间发生 identity replacement 时拒绝旧身份且不产生 web/download 足迹")
    void emptyDownloadBundleStillRevalidatesIdentityAtFinalWebCommit() throws Exception {
        PluginRegistry plugins = new PluginRegistry(List.of());
        RouteAccessRegistry routes = mock(RouteAccessRegistry.class);
        StaticResourceRegistry statics = new AutoRegisteringStaticResourceRegistry(plugins);
        WebI18nBundleRegistry i18n = mock(WebI18nBundleRegistry.class);
        NavigationRegistry navigation = mock(NavigationRegistry.class);
        WebUiSlotRegistry slots = mock(WebUiSlotRegistry.class);
        UserscriptRegistry userscripts = mock(UserscriptRegistry.class);
        ScriptRegistry scripts = mock(ScriptRegistry.class);
        PluginOwnedWebAssetValidator validator = new PluginOwnedWebAssetValidator(statics);
        DownloadExtensionRegistry downloads = new DownloadExtensionRegistry(plugins, statics, validator);
        PluginWebContributionRegistrar registrar = new PluginWebContributionRegistrar(
                routes, statics, i18n, navigation, slots, userscripts, scripts, plugins, downloads);
        CountDownLatch getterEntered = new CountDownLatch(1);
        CountDownLatch releaseGetter = new CountDownLatch(1);
        PluginRegistry.RegisteredPlugin old = external(
                new BlockingEmptyDownloadWebPlugin(getterEntered, releaseGetter),
                "empty-download-web", 1L);
        plugins.register(old);

        AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
        Thread registration = new Thread(() -> {
            try {
                registrar.register(old);
            } catch (Throwable failure) {
                registrationFailure.set(failure);
            }
        }, "old-empty-download-web-registration");
        registration.start();
        assertThat(getterEntered.await(5, TimeUnit.SECONDS)).isTrue();

        plugins.unregister(old.id());
        PluginRegistry.RegisteredPlugin replacement = external(
                new EmptyDownloadWebPlugin(), "empty-download-web", 2L);
        plugins.register(replacement);
        releaseGetter.countDown();
        registration.join(5000);

        assertThat(registration.isAlive()).isFalse();
        assertThat(registrationFailure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current active plugin identity");
        verify(routes, never()).register(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList());
        verify(routes, never()).register(
                org.mockito.ArgumentMatchers.any(PluginRequestOwner.class),
                org.mockito.ArgumentMatchers.anyList());
        verify(routes, never()).unregister(old.id());
        verify(routes, never()).unregister(
                org.mockito.ArgumentMatchers.any(PluginRequestOwner.class));
        assertThat(registrar.currentHandle(old)).isEmpty();
        assertThat(downloads.snapshot().revision()).isZero();
    }

    // --- 夹具 ---

    private record Harness(RouteAccessRegistry route, StaticResourceRegistry staticRes,
                           WebI18nBundleRegistry i18n, NavigationRegistry nav, WebUiSlotRegistry uiSlot,
                           UserscriptRegistry userscripts, ScriptRegistry scripts,
                           PluginWebContributionRegistrar registrar) {
    }

    private record DownloadHarness(PluginRegistry plugins,
                                   RouteAccessRegistry route,
                                   StaticResourceRegistry staticResources,
                                   DownloadExtensionRegistry downloads,
                                   PluginRequestLeaseRegistry requestLeases,
                                   PluginWebContributionRegistrar registrar) {
    }

    private static Harness harness(PluginRegistry base) {
        RouteAccessRegistry route = new RouteAccessRegistry(base);
        StaticResourceRegistry staticRes = new AutoRegisteringStaticResourceRegistry(base);
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

    /** 独立 registrar 测试构造器的 owner 仍先接入真实 PluginRegistry，再消费 prepared static token。 */
    private static final class AutoRegisteringStaticResourceRegistry extends StaticResourceRegistry {
        private final PluginRegistry plugins;

        private AutoRegisteringStaticResourceRegistry(PluginRegistry plugins) {
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

    private static DownloadHarness downloadHarness() {
        return downloadHarness(new PluginRegistry(List.of()));
    }

    private static DownloadHarness downloadHarness(PluginRegistry plugins) {
        return downloadHarness(plugins, new RouteAccessRegistry(plugins));
    }

    private static DownloadHarness downloadHarness(
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

    private static PluginRegistry.RegisteredPlugin external(PixivFeaturePlugin plugin) {
        return new PluginRegistry.RegisteredPlugin(plugin, PluginSource.EXTERNAL, CL);
    }

    private static PluginRegistry.RegisteredPlugin external(PixivFeaturePlugin plugin,
                                                             String packageId,
                                                             long generation) {
        return new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, CL, packageId, generation);
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
                    "web-demo", "web-demo.slot", "demo-anchor", "/web-demo/slot.js", 10));
        }

        @Override
        public List<UserscriptContribution> userscripts() {
            return List.of(new UserscriptContribution("web-demo", "classpath:/test-userscripts/*.user.js"));
        }
    }

    /** 只贡献一个下载类型，并可选贡献普通路由，用于验证统一注册事务与精确代际撤回。 */
    private record DownloadOnlyPlugin(
            String id,
            String type,
            List<WebRouteContribution> routes
    ) implements PixivFeaturePlugin {
        @Override public String displayName() { return "download.plugin.name"; }
        @Override public String description() { return "download.plugin.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<WebRouteContribution> routes() { return routes; }

        @Override
        public List<QueueTypeContribution> queueTypes() {
            return List.of(new QueueTypeContribution(
                    id, type, "download", "kind." + type, 10, null));
        }
    }

    /** 启动期外置 route getter 只允许 Web registrar 读取一次。 */
    private static final class BootRouteReadOncePlugin implements PixivFeaturePlugin {
        private final AtomicInteger routeReads = new AtomicInteger();

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
    private static final class LateGetterErrorPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "late-getter-error"; }
        @Override public String displayName() { return "late-getter-error.name"; }
        @Override public String description() { return "late-getter-error.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<WebRouteContribution> routes() {
            return List.of(WebRouteContribution.admin("/late-getter-error/**"));
        }
        @Override public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution(
                    id(), "classpath:/test-download/", "/late-getter-error/"));
        }
        @Override public List<I18nContribution> i18n() {
            throw new AssertionError("plugin-controlled error text");
        }
    }

    /** 下载 getter 在普通 web 声明之后抛 Error，验证统一注册事务不泄漏半足迹。 */
    private static final class DownloadGetterErrorPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "download-getter-error"; }
        @Override public String displayName() { return "download-getter-error.name"; }
        @Override public String description() { return "download-getter-error.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<WebRouteContribution> routes() {
            return List.of(WebRouteContribution.admin("/download-getter-error/**"));
        }
        @Override public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution(
                    id(), "classpath:/test-download/", "/download-getter-error/"));
        }
        @Override public List<QueueTypeContribution> queueTypes() {
            throw new AssertionError("plugin-controlled download error text");
        }
    }

    /** 第二次静态 getter 返回错误位置；正确实现只读取第一次并复用于 serving 与模块校验。 */
    private static final class FlakyStaticDownloadPlugin implements PixivFeaturePlugin {
        private final AtomicInteger staticReads = new AtomicInteger();

        @Override public String id() { return "snapshot-owner"; }
        @Override public String displayName() { return "snapshot-owner.name"; }
        @Override public String description() { return "snapshot-owner.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }

        @Override
        public List<StaticResourceContribution> staticResources() {
            String location = staticReads.incrementAndGet() == 1
                    ? "classpath:/test-download/"
                    : "classpath:/missing-second-read/";
            return List.of(new StaticResourceContribution(id(), location, "/snapshot/"));
        }

        @Override
        public List<QueueTypeContribution> queueTypes() {
            return List.of(new QueueTypeContribution(
                    id(), "snapshot-type", "download", "kind.snapshot", 10,
                    "/snapshot/module.js"));
        }

        int staticReads() {
            return staticReads.get();
        }
    }

    /** 只有普通 route、下载扩展为空，用于覆盖统一 web 事务的空 bundle 最终身份复核。 */
    private static final class EmptyDownloadWebPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "empty-download-web"; }
        @Override public String displayName() { return "empty-download-web.name"; }
        @Override public String description() { return "empty-download-web.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<WebRouteContribution> routes() {
            return List.of(WebRouteContribution.admin("/empty-download-web/**"));
        }
    }

    /** 在 getter 阶段暂停，用于证明准备不持有 web/plugin registry 锁。 */
    private record BlockingEmptyDownloadWebPlugin(
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

    private record BlockingRoutePlugin(
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

    private static final class FailingRouteAccessRegistry extends RouteAccessRegistry {
        private boolean failBeforeUnregister;

        private FailingRouteAccessRegistry(PluginRegistry plugins) {
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
    private static final class OwnerTrackingRouteAccessRegistry extends RouteAccessRegistry {
        private int ownerlessExternalRegistrations;
        private int exactExternalRegistrations;

        private OwnerTrackingRouteAccessRegistry(PluginRegistry plugins) {
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
    private static Optional<PluginRequestLease> acquire(
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
