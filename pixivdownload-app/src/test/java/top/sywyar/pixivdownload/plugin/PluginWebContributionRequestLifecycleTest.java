package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
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
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.DrilldownRegistry;
import top.sywyar.pixivdownload.plugin.registry.LandingRegistry;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PageSectionRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StartupRouteRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;
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

@DisplayName("插件 Web 贡献：请求准入与精确身份")
class PluginWebContributionRequestLifecycleTest extends PluginWebContributionRegistrarTestSupport {

    @Test
    @DisplayName("同一注册身份重新 serving 后旧句柄不能撤回新 publication")
    void staleServingHandleCannotWithdrawNewDownloadPublication() {
        DownloadHarness h = downloadHarness();
        PluginRegistry.RegisteredPlugin registered = external(
                new DownloadOnlyPlugin("download-owner", "owned-type", List.of()),
                "download-owner", 1L);
        h.plugins.register(registered);
        PluginWebContributionHandle first = h.registrar.register(registered);
        long firstPublication = h.downloads.snapshot().downloadTypes().get(0).publicationId();

        h.registrar.unregister(first);
        PluginWebContributionHandle second = h.registrar.register(registered);
        DownloadExtensionRegistry.Snapshot secondSnapshot = h.downloads.snapshot();

        assertThat(secondSnapshot.downloadTypes()).singleElement().satisfies(item -> {
            assertThat(item.owner().generation()).isEqualTo(1L);
            assertThat(item.publicationId()).isGreaterThan(firstPublication);
        });
        assertThat(second).isNotSameAs(first);
        assertThat(h.registrar.unregister(first)).isFalse();
        assertThat(h.downloads.snapshot()).isSameAs(secondSnapshot);

        h.registrar.unregister(second);
        assertThat(h.downloads.snapshot().downloadTypes()).isEmpty();
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
        assertThat(h.downloads.snapshot().downloadTypes()).isEmpty();
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
}
