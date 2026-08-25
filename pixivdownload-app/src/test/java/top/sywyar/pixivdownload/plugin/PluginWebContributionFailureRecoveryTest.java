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

@DisplayName("插件 Web 贡献：失败回滚与清理重试")
class PluginWebContributionFailureRecoveryTest extends PluginWebContributionRegistrarTestSupport {

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
                .hasMessageContaining("duplicate download type");
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
                .hasMessageContaining("downloadTypes")
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
        assertThat(h.downloads.snapshot().downloadTypes()).singleElement().satisfies(type ->
                assertThat(type.descriptor().moduleUrl()).isEqualTo("/snapshot/module.js"));
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
        assertThat(downloads.snapshot().downloadTypes()).isEmpty();
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
        assertThat(downloads.snapshot().downloadTypes()).singleElement().satisfies(type ->
                assertThat(type.descriptor().type()).isEqualTo("stateful-type"));
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
}
