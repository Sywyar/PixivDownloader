package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry.OwnedQueueOperations;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationService;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.lifecycle.ClassifiedPluginLifecycleException;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar.PreparedOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleState;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPublication;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestGenerationDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLease;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLeaseRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestOwner;
import top.sywyar.pixivdownload.core.schedule.capability.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginStream;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.quiesce.PluginRuntimeTaskQuiescer;
import top.sywyar.pixivdownload.plugin.management.PluginManagementErrorCode;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.LoadedPluginPackage;
import top.sywyar.pixivdownload.plugin.web.PluginAwareRequestMappingHandlerMapping;
import top.sywyar.pixivdownload.plugin.web.PluginControllerRegistrar;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionRegistrar;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionRegistrar.PreparedWebContribution;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionHandle;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebAssetValidator;
import top.sywyar.pixivdownload.scripts.ScriptRegistry;
import top.sywyar.pixivdownload.scripts.UserscriptRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外置插件运行期热启停 / quiesce 生命周期服务测试：
 * <ul>
 *   <li><b>真实子 context 组</b>：启动期接入建立子 context、注入父核心服务、stop 关闭、单插件失败隔离、
 *       start→stop→start 可重复（真实 {@code ApplicationContext} + 真实但空的注册器）。</li>
 *   <li><b>mock 组</b>：六个生命周期动词（load/start/quiesce/stop/unload/reload）的流转、幂等、非法流转诊断，
 *       以及 stop 中某一步异常时 registry 清退仍发生（mock 注册器验证调用顺序与隔离）。</li>
 * </ul>
 */
@DisplayName("外置插件运行期热启停 / quiesce 生命周期服务")
class PluginLifecycleServiceTest {

    // ============================ 真实子 context 组 ============================

    @Test
    @DisplayName("startAll 建立子 context：插件 Bean 注入父核心服务、不在父 context；stopAll 关闭并落 STOPPED")
    void startAllBuildsChildContextThenStopAllCloses() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            PluginContextModule module = new PluginContextModule(
                    "ext-demo", getClass().getClassLoader(), List.of(PluginConfig.class));
            PluginLifecycleService service = realService(parent, List.of(module));

            service.startAll();

            assertThat(service.contextCount()).isEqualTo(1);
            assertThat(service.servingPluginIds()).containsExactly("ext-demo");
            assertThat(service.phase("ext-demo")).contains(PluginRuntimePhase.STARTED);
            ConfigurableApplicationContext child = service.contextFor("ext-demo").orElseThrow();
            assertThat(child.getBean(PluginBean.class).coreService())
                    .isSameAs(parent.getBean(CoreApiService.class));
            assertThat(parent.getBeanNamesForType(PluginBean.class)).isEmpty();

            service.stopAll();

            assertThat(service.contextCount()).isZero();
            assertThat(child.isActive()).isFalse();
            assertThat(service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
        }
    }

    @Test
    @DisplayName("无外置插件：服务为空、透明无副作用")
    void noExternalPluginsIsTransparent() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            PluginLifecycleService service = realService(parent, List.of());

            service.startAll();

            assertThat(service.contextCount()).isZero();
            assertThat(service.servingPluginIds()).isEmpty();
            assertThat(service.managedPluginIds()).isEmpty();

            service.stopAll();
        }
    }

    @Test
    @DisplayName("单个插件子 context 建立失败被隔离：好插件照常 STARTED、坏插件 STOPPED 无 context")
    void failingPluginContextIsIsolated() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            PluginContextModule broken = new PluginContextModule(
                    "ext-broken", getClass().getClassLoader(), List.of(BrokenPluginConfig.class));
            PluginContextModule good = new PluginContextModule(
                    "ext-good", getClass().getClassLoader(), List.of(PluginConfig.class));
            PluginLifecycleService service = realService(parent, List.of(broken, good));

            service.startAll();

            assertThat(service.servingPluginIds()).containsExactly("ext-good");
            assertThat(service.phase("ext-good")).contains(PluginRuntimePhase.STARTED);
            assertThat(service.contextFor("ext-broken")).isEmpty();
            assertThat(service.phase("ext-broken")).contains(PluginRuntimePhase.STOPPED);

            service.stopAll();
        }
    }

    @Test
    @DisplayName("start→stop→start 可重复：stop 关闭旧 context、start 重建新 context，阶段往返一致")
    void startStopStartIsRepeatable() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            PluginContextModule module = new PluginContextModule(
                    "ext-demo", getClass().getClassLoader(), List.of(PluginConfig.class));
            PluginLifecycleService service = realService(parent, List.of(module));

            service.startAll();
            ConfigurableApplicationContext first = service.contextFor("ext-demo").orElseThrow();

            service.stop("ext-demo");
            assertThat(service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
            assertThat(first.isActive()).isFalse();
            assertThat(service.contextFor("ext-demo")).isEmpty();

            service.start("ext-demo");
            assertThat(service.phase("ext-demo")).contains(PluginRuntimePhase.STARTED);
            ConfigurableApplicationContext second = service.contextFor("ext-demo").orElseThrow();
            assertThat(second.isActive()).isTrue();
            assertThat(second).isNotSameAs(first);

            service.stopAll();
        }
    }

    @Test
    @DisplayName("quiesce 标记后 stop：阶段经 QUIESCED 落到 STOPPED")
    void quiesceThenStopRealContext() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            PluginContextModule module = new PluginContextModule(
                    "ext-demo", getClass().getClassLoader(), List.of(PluginConfig.class));
            PluginLifecycleService service = realService(parent, List.of(module));
            service.startAll();

            service.quiesce("ext-demo");
            assertThat(service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);

            service.stop("ext-demo");
            assertThat(service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);

            service.stopAll();
        }
    }

    // ============================ mock 组（按 pluginId 动词 + 隔离）============================

    /** 一个纯贡献外置插件（无子 context）的 mock 装置：注册器全 mock，便于验证调用与异常隔离。 */
    private static final class MockHarness {
        final PluginControllerRegistrar controllerRegistrar = mock(PluginControllerRegistrar.class);
        final PluginWebContributionRegistrar webRegistrar = mock(PluginWebContributionRegistrar.class);
        final PluginScheduleContributionRegistrar scheduleRegistrar = mock(PluginScheduleContributionRegistrar.class);
        final PluginCapabilityContributionRegistrar capabilityRegistrar =
                mock(PluginCapabilityContributionRegistrar.class);
        final PluginRegistry registry = mock(PluginRegistry.class);
        final PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
        final PluginLifecycleState state = new PluginLifecycleState();
        final RecordingPlugin plugin = new RecordingPlugin("ext-demo");
        final PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, MockHarness.class.getClassLoader());
        final PluginWebContributionHandle bootWebHandle = mock(PluginWebContributionHandle.class);
        final PluginWebContributionHandle runtimeWebHandle = mock(PluginWebContributionHandle.class);
        final PluginRequestGenerationDrain requestDrain = mock(PluginRequestGenerationDrain.class);
        final ScheduleCapabilityPublication publication = mock(ScheduleCapabilityPublication.class);
        final ScheduleGenerationDrain drain = mock(ScheduleGenerationDrain.class);
        final RecordingQueueOperations ops;          // 非空当且仅当装置声明了 queueType
        final QueueOperationRegistry queueRegistry;
        final PluginStreamRegistry streamRegistry = new PluginStreamRegistry();
        final RecordingStream stream = new RecordingStream();
        final PluginLifecycleService service;

        MockHarness() {
            this(null);
        }

        /** {@code queueType != null} 时让 ext-demo 声明该作品类型并注册对应队列操作适配器（验证 drain）。 */
        MockHarness(String queueType) {
            plugin.queueType = queueType;
            if (queueType != null) {
                ops = new RecordingQueueOperations(queueType);
                queueRegistry = new QueueOperationRegistry(List.of());
                queueRegistry.register("ext-demo", List.of(ops));
            } else {
                ops = null;
                queueRegistry = new QueueOperationRegistry(List.of());
            }
            when(runtime.inspectContextModules()).thenReturn(List.of());
            when(registry.registeredPlugins()).thenReturn(List.of(registered));
            delegateFeatureCallbacks(registry, registered);
            when(registry.containsIdentity(same(registered))).thenReturn(true, false);
            when(webRegistrar.currentHandle(same(registered))).thenReturn(Optional.of(bootWebHandle));
            when(webRegistrar.withdrawRequests(same(bootWebHandle))).thenReturn(Optional.of(requestDrain));
            when(webRegistrar.withdrawRequests(same(runtimeWebHandle))).thenReturn(Optional.of(requestDrain));
            PreparedWebContribution preparedWeb = mock(PreparedWebContribution.class);
            when(webRegistrar.prepare(same(registered))).thenReturn(preparedWeb);
            when(webRegistrar.commit(same(preparedWeb))).thenReturn(runtimeWebHandle);
            when(scheduleRegistrar.register(any(), eq(registered), any())).thenReturn(Optional.of(publication));
            when(scheduleRegistrar.withdraw(any(), eq(publication))).thenReturn(Optional.of(drain));
            when(requestDrain.awaitDrained(anyLong())).thenReturn(true);
            when(requestDrain.isDrained()).thenReturn(true);
            when(drain.awaitDrained(anyLong())).thenReturn(true);
            when(drain.isDrained()).thenReturn(true);
            service = new PluginLifecycleService(mock(ApplicationContext.class), runtime,
                    new PluginApplicationContextFactory(), controllerRegistrar, webRegistrar, scheduleRegistrar,
                    runtimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry),
                    capabilityRegistrar, registry, state);
            service.startAll(); // 纯贡献插件登记为 STARTED
            // 注册一条该插件拥有的 SSE 推流（验证 quiesce / 卸载时被关闭）。
            streamRegistry.register("ext-demo", "conn-1", stream);
        }
    }

    @Test
    @DisplayName("startAll 纳管纯贡献外置插件：登记为 STARTED")
    void startAllAdoptsPureContributionPlugin() {
        MockHarness h = new MockHarness();
        assertThat(h.service.managedPluginIds()).containsExactly("ext-demo");
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STARTED);
        assertThat(h.service.contextCount()).isZero(); // 无子 context
    }

    @Test
    @DisplayName("quiesce：阶段转 QUIESCED、可观测；对非 STARTED 插件 quiesce 抛清晰诊断")
    void quiesceTransitionsAndRejectsNonStarted() {
        MockHarness h = new MockHarness();

        h.service.quiesce("ext-demo");
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);
        assertThat(h.state.isQuiesced("ext-demo")).isTrue();
        // 幂等
        h.service.quiesce("ext-demo");
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);

        h.service.stop("ext-demo");
        assertThatThrownBy(() -> h.service.quiesce("ext-demo"))
                .isInstanceOf(PluginLifecycleException.class);
    }

    @Test
    @DisplayName("quiesce 精确撤回 schedule publication；随后 stop 只等待同一 drain、不重复撤回")
    void quiesceUnregistersScheduleContributionThenStopIsIdempotent() {
        MockHarness h = new MockHarness();

        h.service.quiesce("ext-demo");
        verify(h.scheduleRegistrar).withdraw(any(), eq(h.publication));
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);

        clearInvocations(h.scheduleRegistrar);
        h.service.stop("ext-demo");
        verify(h.scheduleRegistrar, never()).withdraw(any(), any());
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
    }

    @Test
    @DisplayName("stop：精确撤回 publication 后拆 controller/web 并调插件 stop()；重复 stop 幂等")
    void stopTearsDownAndIsIdempotent() {
        MockHarness h = new MockHarness();

        h.service.stop("ext-demo");

        verify(h.controllerRegistrar).unregisterControllers("ext-demo");
        verify(h.scheduleRegistrar).withdraw(any(), eq(h.publication));
        verify(h.webRegistrar).unregister(same(h.bootWebHandle));
        assertThat(h.plugin.stopCount).isEqualTo(1);
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);

        // 重复 stop 不破坏状态、不再次清退（已 STOPPED → 早返回，schedule / web 注销与插件 stop() 都不再发生）
        clearInvocations(h.scheduleRegistrar, h.webRegistrar);
        h.service.stop("ext-demo");
        verify(h.scheduleRegistrar, never()).withdraw(any(), any());
        verify(h.webRegistrar, never()).unregister(same(h.bootWebHandle));
        assertThat(h.plugin.stopCount).isEqualTo(1);
    }

    @Test
    @DisplayName("controller 清理失败时保持 QUIESCED，重试只继续未完成步骤后才关闭")
    void stopStepFailureKeepsRetryableQuiescedState() {
        MockHarness h = new MockHarness();
        doThrow(new RuntimeException("boom")).doNothing()
                .when(h.controllerRegistrar).unregisterControllers("ext-demo");

        assertThatThrownBy(() -> h.service.stop("ext-demo"))
                .isInstanceOf(PluginLifecycleException.class)
                .hasMessageContaining("cleanup remains pending");

        verify(h.webRegistrar).unregister(same(h.bootWebHandle)); // 仍清退当前精确 serving 的 web 贡献
        assertThat(h.plugin.stopCount).isZero();
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);

        h.service.stop("ext-demo");

        verify(h.controllerRegistrar, times(2)).unregisterControllers("ext-demo");
        assertThat(h.plugin.stopCount).isEqualTo(1);
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
    }

    @Test
    @DisplayName("web serving 已撤回但后段 cleanup 报错时清除旧句柄，随后 start 可重新注册")
    void withdrawnWebHandleIsClearedAfterCleanupFailureAndRestartable() {
        MockHarness h = new MockHarness();
        doThrow(new AssertionError("cleanup failed"))
                .when(h.webRegistrar).unregister(same(h.bootWebHandle));
        when(h.webRegistrar.isCurrent(same(h.bootWebHandle))).thenReturn(false);

        h.service.stop("ext-demo");
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);

        h.service.start("ext-demo");
        verify(h.webRegistrar).prepare(same(h.registered));
        verify(h.webRegistrar).commit(any(PreparedWebContribution.class));
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STARTED);
    }

    @Test
    @DisplayName("下载 publication 前置撤回失败并恢复 serving 时阻断 stop 且保留 QUIESCED")
    void currentWebHandleAfterWithdrawalFailureBlocksStop() {
        MockHarness h = new MockHarness();
        doThrow(new IllegalStateException("withdraw failed"))
                .when(h.webRegistrar).unregister(same(h.bootWebHandle));
        when(h.webRegistrar.isCurrent(same(h.bootWebHandle))).thenReturn(true);

        assertThatThrownBy(() -> h.service.stop("ext-demo"))
                .isInstanceOf(PluginLifecycleException.class)
                .hasMessageContaining("web serving remains current");
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);
        assertThat(h.plugin.stopCount).isZero();
    }

    @Test
    @DisplayName("unload：先停止再从核心注册中心移除，阶段落 UNLOADED")
    void unloadStopsThenUnregistersFromCore() {
        MockHarness h = new MockHarness();

        h.service.unload("ext-demo");

        verify(h.scheduleRegistrar).withdraw(any(), eq(h.publication));
        verify(h.webRegistrar).unregister(same(h.bootWebHandle)); // 经 stop 拆服务足迹
        verify(h.registry).unregister(same(h.registered));         // 从核心注册中心移除精确代际
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.UNLOADED);
        // 幂等
        h.service.unload("ext-demo");
        verify(h.registry, times(1)).unregister(same(h.registered));
    }

    @Test
    @DisplayName("load：把已卸下插件重新接入核心注册中心，阶段落 LOADED；非 UNLOADED 时 load 抛诊断")
    void loadReregistersIntoCore() {
        MockHarness h = new MockHarness();
        h.service.unload("ext-demo"); // → UNLOADED

        h.service.load("ext-demo");

        verify(h.registry).register(h.registered);
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.LOADED);

        // load 之后可再 start（重新接入 web 贡献 + 重新调插件 start()）
        h.service.start("ext-demo");
        verify(h.webRegistrar).prepare(h.registered);
        verify(h.webRegistrar).commit(any(PreparedWebContribution.class));
        assertThat(h.plugin.startCount).isEqualTo(1);
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STARTED);
    }

    @Test
    @DisplayName("adopt 状态提交失败按逆序撤销核心身份且清理致命错误优先")
    void adoptFailureRollsBackExactRegistrationWithFatalPriority() {
        PluginRegistry registry = mock(PluginRegistry.class);
        PluginLifecycleState state = new PluginLifecycleState() {
            @Override
            public void initialize(String pluginId, PluginRuntimePhase phase) {
                throw new IllegalStateException("initialize-failed");
            }

            @Override
            public void remove(String pluginId) {
                throw new IllegalArgumentException("state-cleanup-failed");
            }
        };
        OutOfMemoryError cleanupFatal = new OutOfMemoryError("unregister-fatal");
        when(registry.containsIdentity(any(PluginRegistry.RegisteredPlugin.class))).thenReturn(true);
        doThrow(cleanupFatal).when(registry).unregister(any(PluginRegistry.RegisteredPlugin.class));
        RecordingPlugin plugin = new RecordingPlugin("ext-adopt");
        PluginInstallation installation = mock(PluginInstallation.class);
        when(installation.registrable()).thenReturn(true);
        when(installation.id()).thenReturn("ext-adopt");
        when(installation.plugin()).thenReturn(plugin);
        when(installation.classLoader()).thenReturn(getClass().getClassLoader());
        PluginInventory inventory = mock(PluginInventory.class);
        when(inventory.installations()).thenReturn(List.of(installation));
        LoadedPluginPackage loaded = mock(LoadedPluginPackage.class);
        when(loaded.packageId()).thenReturn("ext-adopt");
        when(loaded.generation()).thenReturn(9L);
        when(loaded.inventory()).thenReturn(inventory);
        when(loaded.contextModules()).thenReturn(List.of());
        PluginLifecycleService service = new PluginLifecycleService(
                mock(ApplicationContext.class), mock(PluginRuntimeManager.class),
                new PluginApplicationContextFactory(), mock(PluginControllerRegistrar.class),
                mock(PluginWebContributionRegistrar.class), mock(PluginScheduleContributionRegistrar.class),
                mock(PluginRuntimeTaskQuiescer.class), mock(PluginCapabilityContributionRegistrar.class),
                registry, state);

        assertThatThrownBy(() -> service.adoptLoadedPackage(loaded)).isSameAs(cleanupFatal);

        assertThat(service.managedPluginIds()).isEmpty();
        assertThat(cleanupFatal.getSuppressed()).anySatisfy(failure ->
                assertThat(failure).hasMessage("state-cleanup-failed"));
        assertThat(cleanupFatal.getSuppressed()).anySatisfy(failure ->
                assertThat(failure).hasMessage("initialize-failed"));
        ArgumentCaptor<PluginRegistry.RegisteredPlugin> registered =
                ArgumentCaptor.forClass(PluginRegistry.RegisteredPlugin.class);
        verify(registry).register(registered.capture());
        verify(registry).unregister(same(registered.getValue()));
    }

    @Test
    @DisplayName("reload：stop 后再 start，阶段回到 STARTED、web 贡献重新接入、插件 stop()/start() 各一次")
    void reloadRecyclesServingFootprint() {
        MockHarness h = new MockHarness();

        h.service.reload("ext-demo");

        verify(h.webRegistrar).unregister(same(h.bootWebHandle));
        verify(h.webRegistrar).prepare(h.registered);
        verify(h.webRegistrar).commit(any(PreparedWebContribution.class));
        assertThat(h.plugin.stopCount).isEqualTo(1);
        assertThat(h.plugin.startCount).isEqualTo(1);
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STARTED);
    }

    @Test
    @DisplayName("未知 pluginId：生命周期动词抛清晰诊断")
    void unknownPluginIsRejected() {
        MockHarness h = new MockHarness();
        assertThatThrownBy(() -> h.service.stop("ghost"))
                .isInstanceOf(PluginLifecycleException.class)
                .hasMessageContaining("unknown external plugin");
    }

    // ============================ 运行期任务清退组（quiesce / 卸载时 drain 在途队列 + 关闭 SSE）============================

    @Test
    @DisplayName("stop：保存该插件 queue generation drain、发送取消并关闭 SSE，阶段落 STOPPED")
    void stopDrainsQueueTasksAndClosesStreams() {
        MockHarness h = new MockHarness("ext-illust");

        h.service.stop("ext-demo");

        assertThat(h.ops.clearAllCount).isEqualTo(1);                       // 保存 drain 后发送协作式取消
        assertThat(h.stream.closedCount).isEqualTo(1);                      // SSE 推流被关闭（客户端收到不可用事件）
        assertThat(h.streamRegistry.activeStreamCount("ext-demo")).isZero(); // 关闭后不残留引用
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
    }

    @Test
    @DisplayName("quiesce：同样排空在途队列 + 关闭 SSE（quiesce 即停新 + 清退在途），阶段为 QUIESCED")
    void quiesceDrainsQueueTasksAndClosesStreams() {
        MockHarness h = new MockHarness("ext-illust");

        h.service.quiesce("ext-demo");

        assertThat(h.ops.clearAllCount).isEqualTo(1);
        assertThat(h.stream.closedCount).isEqualTo(1);
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);
    }

    @Test
    @DisplayName("downloadTypes getter 已不可安全读取时 quiesce 仍完成且 stop 不会二次撤回 schedule")
    void downloadTypeGetterAssertionErrorDoesNotStrandQuiesce() {
        MockHarness h = new MockHarness();
        h.plugin.failDownloadTypesWithError = true;

        h.service.quiesce("ext-demo");

        assertThat(h.stream.closedCount).isEqualTo(1);
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);
        h.service.stop("ext-demo");
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
        verify(h.scheduleRegistrar).withdraw(any(), eq(h.publication));
    }

    @Test
    @DisplayName("队列取消抛断言错误时保持 QUIESCED，修复后重试才拆服务足迹")
    void queueCancellationAssertionErrorKeepsRetryableQuiesce() {
        MockHarness h = new MockHarness("ext-illust");
        h.ops.failClearAllWithError = true;

        assertThatThrownBy(() -> h.service.stop("ext-demo"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("plugin-private-clear-all");

        assertThat(h.ops.clearAllCount).isEqualTo(1);
        assertThat(h.stream.closedCount).isEqualTo(1);
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);
        verify(h.scheduleRegistrar).withdraw(any(), eq(h.publication));
        verify(h.webRegistrar, never()).unregister(same(h.bootWebHandle));

        h.ops.failClearAllWithError = false;
        h.service.stop("ext-demo");

        assertThat(h.ops.clearAllCount).isEqualTo(2);
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
        verify(h.webRegistrar).unregister(same(h.bootWebHandle));
    }

    @Test
    @DisplayName("无 queueType 的纯贡献插件：stop 不触达队列注册中心（drain 安全空操作），仍关闭其 SSE")
    void stopWithoutQueueTypeStillClosesStreams() {
        MockHarness h = new MockHarness(); // 无 queueType

        h.service.stop("ext-demo");

        assertThat(h.stream.closedCount).isEqualTo(1);
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
    }

    @Test
    @DisplayName("运行任务取消后 drain 等宿主 wrapper 实际退出，unload 才继续")
    void unloadWaitsForRunningQueueWrapperToActuallyExit() throws Exception {
        MockHarness h = new MockHarness("ext-illust");
        CountDownLatch taskEntered = new CountDownLatch(1);
        CountDownLatch cancellationObserved = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        QueueTaskTracker.Task tracked = h.ops.queuedTask(() -> {
            taskEntered.countDown();
            try {
                if (!releaseTask.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release tracked queue task");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("tracked queue task interrupted");
            }
        }, cancellationObserved::countDown);
        Thread worker = new Thread(tracked, "tracked-plugin-queue-task");
        worker.start();
        assertThat(taskEntered.await(5, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Throwable> unloadFailure = new AtomicReference<>();
        Thread unload = new Thread(() -> {
            try {
                h.service.unload("ext-demo");
            } catch (Throwable failure) {
                unloadFailure.set(failure);
            }
        }, "plugin-unload-await-queue");
        unload.start();
        assertThat(cancellationObserved.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(unload.isAlive()).isTrue();
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);
        verify(h.webRegistrar, never()).unregister(same(h.bootWebHandle));

        releaseTask.countDown();
        worker.join(5000);
        unload.join(5000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(unload.isAlive()).isFalse();
        assertThat(unloadFailure.get()).isNull();
        assertThat(h.ops.clearAllCount).isEqualTo(1);
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.UNLOADED);
    }

    // ============================ 停派发先于清退在途组（direct stop / unload / reload from STARTED）============================

    /**
     * 全 mock 协作者（含 mock 的 {@link PluginStreamRegistry} / {@link QueueOperationRegistry}）的装置：用 {@link InOrder}
     * 验证「精确撤回 schedule publication 先于清退在途（关 SSE → drain 队列）」——即 direct stop / unload / reload
     * 从 STARTED 进入 {@code doStop} 时也先 shield 再 drain，drain 窗口内调度器解析不到其来源 / 执行器、不再派发新一轮 run。
     */
    private static final class OrderHarness {
        final PluginControllerRegistrar controllerRegistrar = mock(PluginControllerRegistrar.class);
        final PluginWebContributionRegistrar webRegistrar = mock(PluginWebContributionRegistrar.class);
        final PluginScheduleContributionRegistrar scheduleRegistrar = mock(PluginScheduleContributionRegistrar.class);
        final PluginRegistry registry = mock(PluginRegistry.class);
        final PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
        final PluginLifecycleState state = new PluginLifecycleState();
        final RecordingPlugin plugin = new RecordingPlugin("ext-demo");
        final PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, OrderHarness.class.getClassLoader());
        final PluginWebContributionHandle bootWebHandle = mock(PluginWebContributionHandle.class);
        final PluginWebContributionHandle runtimeWebHandle = mock(PluginWebContributionHandle.class);
        final PluginRequestGenerationDrain requestDrain = mock(PluginRequestGenerationDrain.class);
        final ScheduleCapabilityPublication publication = mock(ScheduleCapabilityPublication.class);
        final ScheduleGenerationDrain drain = mock(ScheduleGenerationDrain.class);
        final PluginStreamRegistry streamRegistry = mock(PluginStreamRegistry.class);
        final QueueOperationRegistry queueRegistry = mock(QueueOperationRegistry.class);
        final QueueOperations queueOperations = mock(QueueOperations.class);
        final QueueGenerationDrain queueDrain = mock(QueueGenerationDrain.class);
        final PluginCapabilityContributionRegistrar capabilityRegistrar =
                mock(PluginCapabilityContributionRegistrar.class);
        final PluginLifecycleService service;

        OrderHarness() {
            when(runtime.inspectContextModules()).thenReturn(List.of());
            when(registry.registeredPlugins()).thenReturn(List.of(registered));
            delegateFeatureCallbacks(registry, registered);
            when(registry.containsIdentity(same(registered))).thenReturn(true, false);
            when(webRegistrar.currentHandle(same(registered))).thenReturn(Optional.of(bootWebHandle));
            when(webRegistrar.withdrawRequests(same(bootWebHandle))).thenReturn(Optional.of(requestDrain));
            when(webRegistrar.withdrawRequests(same(runtimeWebHandle))).thenReturn(Optional.of(requestDrain));
            PreparedWebContribution preparedWeb = mock(PreparedWebContribution.class);
            when(webRegistrar.prepare(same(registered))).thenReturn(preparedWeb);
            when(webRegistrar.commit(same(preparedWeb))).thenReturn(runtimeWebHandle);
            when(scheduleRegistrar.register(any(), eq(registered), any())).thenReturn(Optional.of(publication));
            when(scheduleRegistrar.withdraw(any(), eq(publication))).thenReturn(Optional.of(drain));
            when(requestDrain.awaitDrained(anyLong())).thenReturn(true);
            when(requestDrain.isDrained()).thenReturn(true);
            when(drain.awaitDrained(anyLong())).thenReturn(true);
            when(drain.isDrained()).thenReturn(true);
            when(queueRegistry.operationsForOwner("ext-demo"))
                    .thenReturn(List.of(new OwnedQueueOperations("ext-illust", queueOperations)));
            when(queueOperations.prepareQuiesce("ext-illust")).thenReturn(queueDrain);
            when(queueDrain.queueType()).thenReturn("ext-illust");
            when(queueDrain.generation()).thenReturn(1L);
            when(queueDrain.awaitDrained(anyLong())).thenReturn(true);
            when(queueDrain.isDrained()).thenReturn(true);
            service = new PluginLifecycleService(mock(ApplicationContext.class), runtime,
                    new PluginApplicationContextFactory(), controllerRegistrar, webRegistrar, scheduleRegistrar,
                    runtimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry),
                    capabilityRegistrar, registry, state);
            service.startAll(); // 纯贡献插件登记为 STARTED
            clearInvocations(webRegistrar, requestDrain, scheduleRegistrar, streamRegistry, queueRegistry,
                    queueOperations, queueDrain, capabilityRegistrar);
        }

        /** 断言 request → publication 撤回 → 保存 queue drain → 关 SSE / 发取消 → 同截止时间等待 → 注销。 */
        void verifyShieldThenDrain() {
            InOrder ord = inOrder(webRegistrar, requestDrain, scheduleRegistrar, drain,
                    streamRegistry, queueRegistry, queueOperations, queueDrain, capabilityRegistrar);
            ord.verify(webRegistrar).withdrawRequests(same(bootWebHandle)); // ① 先拒绝新 HTTP 请求
            ord.verify(scheduleRegistrar).withdraw(any(), eq(publication)); // ② 再拒绝新 schedule lease 并取消旧代
            ord.verify(queueRegistry).operationsForOwner("ext-demo"); // ③ 先捕获并保存队列 generation drain
            ord.verify(queueOperations).prepareQuiesce("ext-illust");
            ord.verify(streamRegistry).closeForPlugin("ext-demo"); // ④ 再关闭 SSE 推流
            ord.verify(queueRegistry).operationsForOwner("ext-demo");
            ord.verify(queueOperations).prepareQuiesce("ext-illust");
            ord.verify(queueOperations).cancelQuiescedTasks(); // ⑤ drain 已保存后才发 callback
            ArgumentCaptor<Long> requestDeadline = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Long> scheduleDeadline = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Long> queueDeadline = ArgumentCaptor.forClass(Long.class);
            ord.verify(requestDrain).awaitDrained(requestDeadline.capture());
            ord.verify(drain).awaitDrained(scheduleDeadline.capture());
            ord.verify(queueDrain).awaitDrained(queueDeadline.capture());
            assertThat(scheduleDeadline.getValue()).isEqualTo(requestDeadline.getValue());
            assertThat(queueDeadline.getValue()).isEqualTo(requestDeadline.getValue());
            ord.verify(streamRegistry).closeForPlugin("ext-demo"); // ⑥ request drain 后复核迟到 stream
            ord.verify(streamRegistry).activeStreamCount("ext-demo");
            ord.verify(webRegistrar).unregister(same(bootWebHandle)); // ⑦ drain 归零后 registrar 才 retire serving
        }
    }

    @Test
    @DisplayName("direct stop（from STARTED）：schedule publication 撤回发生在关 SSE / drain 队列之前")
    void directStopShieldsScheduleBeforeDrain() {
        OrderHarness h = new OrderHarness();

        h.service.stop("ext-demo");

        h.verifyShieldThenDrain();
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
    }

    @Test
    @DisplayName("unload（from STARTED）：经 doStop 同样先停派发再清退在途，最终落 UNLOADED")
    void unloadFromStartedShieldsScheduleBeforeDrain() {
        OrderHarness h = new OrderHarness();

        h.service.unload("ext-demo");

        h.verifyShieldThenDrain();
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.UNLOADED);
    }

    @Test
    @DisplayName("reload（from STARTED）：stop 段先停派发再清退在途，随后 start 重建足迹回 STARTED")
    void reloadFromStartedShieldsBeforeDrainThenRestarts() {
        OrderHarness h = new OrderHarness();

        h.service.reload("ext-demo"); // STARTED → stop（先 shield 再 drain）→ start（重建足迹）

        h.verifyShieldThenDrain();
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STARTED);
    }

    @Test
    @DisplayName("运行期 stop 等待 lease 超时返回忙碌并保持 QUIESCED，lease 归零后重试才关闭 context")
    void runtimeStopTimeoutKeepsContextAliveUntilRetry() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            PluginControllerRegistrar controllerRegistrar = mock(PluginControllerRegistrar.class);
            PluginWebContributionRegistrar webRegistrar = mock(PluginWebContributionRegistrar.class);
            PluginScheduleContributionRegistrar scheduleRegistrar = mock(PluginScheduleContributionRegistrar.class);
            PluginRuntimeTaskQuiescer quiescer = mock(PluginRuntimeTaskQuiescer.class);
            PluginCapabilityContributionRegistrar capabilityRegistrar =
                    mock(PluginCapabilityContributionRegistrar.class);
            PluginRegistry registry = mock(PluginRegistry.class);
            PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
            RecordingPlugin plugin = new RecordingPlugin("ext-demo");
            PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                    plugin, PluginSource.EXTERNAL, getClass().getClassLoader());
            PluginWebContributionHandle bootWebHandle = mock(PluginWebContributionHandle.class);
            ScheduleCapabilityPublication publication = mock(ScheduleCapabilityPublication.class);
            ScheduleGenerationDrain drain = mock(ScheduleGenerationDrain.class);
            PluginContextModule module = new PluginContextModule(
                    "ext-demo", getClass().getClassLoader(), List.of(PluginConfig.class));
            when(runtime.inspectContextModules()).thenReturn(List.of(module));
            when(registry.registeredPlugins()).thenReturn(List.of(registered));
            delegateFeatureCallbacks(registry, registered);
            when(webRegistrar.currentHandle(same(registered))).thenReturn(Optional.of(bootWebHandle));
            when(scheduleRegistrar.register(any(), eq(registered), any())).thenReturn(Optional.of(publication));
            when(quiescer.withdrawSchedule(any(), eq(publication)))
                    .thenReturn(new PluginRuntimeTaskQuiescer.QuiesceResult(Optional.of(drain)));
            when(drain.awaitDrained(anyLong())).thenReturn(false, true);
            when(drain.isDrained()).thenReturn(true);
            when(drain.activeLeaseCount()).thenReturn(1);
            PluginLifecycleService service = new PluginLifecycleService(
                    parent, runtime, new PluginApplicationContextFactory(), controllerRegistrar, webRegistrar,
                    scheduleRegistrar, quiescer, capabilityRegistrar, registry, new PluginLifecycleState());
            service.startAll();
            ConfigurableApplicationContext child = service.contextFor("ext-demo").orElseThrow();

            assertThatThrownBy(() -> service.stop("ext-demo"))
                    .isInstanceOfSatisfying(ClassifiedPluginLifecycleException.class, failure ->
                            assertThat(failure.code()).isEqualTo(PluginManagementErrorCode.OPERATION_IN_PROGRESS));

            assertThat(service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);
            assertThat(child.isActive()).isTrue();
            assertThat(service.contextFor("ext-demo")).contains(child);
            assertThat(plugin.stopCount).isZero();
            verify(controllerRegistrar, never()).unregisterControllers("ext-demo");
            verify(capabilityRegistrar, never()).retireDrained(any());
            verify(webRegistrar, never()).unregister(same(bootWebHandle));

            service.stop("ext-demo");

            assertThat(service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
            assertThat(child.isActive()).isFalse();
            assertThat(service.contextFor("ext-demo")).isEmpty();
            assertThat(plugin.stopCount).isEqualTo(1);
            verify(quiescer, times(1)).withdrawSchedule(any(), eq(publication));
            verify(quiescer, times(1)).prepareQueueDrains(eq("ext-demo"), any(), any());
            verify(quiescer, times(1)).quiesceAfterScheduleWithdrawal(eq("ext-demo"), any());
        }
    }

    @Test
    @DisplayName("stopAll 按 owner 先撤请求与 publication，中断也无界等 request drain 后才逆序拆足迹")
    void stopAllWithdrawsEveryPublicationBeforeUnboundedDrainWait() {
        RecordingPlugin first = new RecordingPlugin("ext-first");
        RecordingPlugin second = new RecordingPlugin("ext-second");
        PluginRegistry.RegisteredPlugin firstRegistered = new PluginRegistry.RegisteredPlugin(
                first, PluginSource.EXTERNAL, getClass().getClassLoader());
        PluginRegistry.RegisteredPlugin secondRegistered = new PluginRegistry.RegisteredPlugin(
                second, PluginSource.EXTERNAL, getClass().getClassLoader());
        PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
        PluginRegistry registry = mock(PluginRegistry.class);
        PluginControllerRegistrar controllerRegistrar = mock(PluginControllerRegistrar.class);
        PluginWebContributionRegistrar webRegistrar = mock(PluginWebContributionRegistrar.class);
        PluginWebContributionHandle firstWebHandle = mock(PluginWebContributionHandle.class);
        PluginWebContributionHandle secondWebHandle = mock(PluginWebContributionHandle.class);
        PluginRequestGenerationDrain firstRequestDrain = mock(PluginRequestGenerationDrain.class);
        PluginRequestGenerationDrain secondRequestDrain = mock(PluginRequestGenerationDrain.class);
        PluginScheduleContributionRegistrar scheduleRegistrar = mock(PluginScheduleContributionRegistrar.class);
        PluginStreamRegistry streamRegistry = mock(PluginStreamRegistry.class);
        PluginCapabilityContributionRegistrar capabilityRegistrar =
                mock(PluginCapabilityContributionRegistrar.class);
        ScheduleCapabilityPublication firstPublication = mock(ScheduleCapabilityPublication.class);
        ScheduleCapabilityPublication secondPublication = mock(ScheduleCapabilityPublication.class);
        ScheduleGenerationDrain firstDrain = mock(ScheduleGenerationDrain.class);
        ScheduleGenerationDrain secondDrain = mock(ScheduleGenerationDrain.class);
        when(runtime.inspectContextModules()).thenReturn(List.of());
        when(registry.registeredPlugins()).thenReturn(List.of(firstRegistered, secondRegistered));
        when(webRegistrar.currentHandle(same(firstRegistered))).thenReturn(Optional.of(firstWebHandle));
        when(webRegistrar.currentHandle(same(secondRegistered))).thenReturn(Optional.of(secondWebHandle));
        when(webRegistrar.withdrawRequests(same(firstWebHandle))).thenReturn(Optional.of(firstRequestDrain));
        when(webRegistrar.withdrawRequests(same(secondWebHandle))).thenReturn(Optional.of(secondRequestDrain));
        when(scheduleRegistrar.register(any(), eq(firstRegistered), any())).thenReturn(Optional.of(firstPublication));
        when(scheduleRegistrar.register(any(), eq(secondRegistered), any())).thenReturn(Optional.of(secondPublication));
        when(scheduleRegistrar.withdraw(any(), eq(firstPublication))).thenReturn(Optional.of(firstDrain));
        when(scheduleRegistrar.withdraw(any(), eq(secondPublication))).thenReturn(Optional.of(secondDrain));
        when(firstRequestDrain.isDrained()).thenReturn(true);
        when(secondRequestDrain.isDrained()).thenAnswer(invocation -> {
            verify(webRegistrar).withdrawRequests(same(secondWebHandle));
            verify(webRegistrar).withdrawRequests(same(firstWebHandle));
            verify(scheduleRegistrar).withdraw(any(), eq(secondPublication));
            verify(scheduleRegistrar).withdraw(any(), eq(firstPublication));
            return false;
        }).thenReturn(false).thenReturn(true);
        when(secondRequestDrain.awaitDrained()).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return false;
        }).thenReturn(true);
        when(firstDrain.isDrained()).thenReturn(true);
        when(secondDrain.isDrained()).thenReturn(true);
        PluginRuntimeTaskQuiescer quiescer = runtimeTaskQuiescer(
                scheduleRegistrar, streamRegistry, new QueueOperationRegistry(List.of()));
        PluginLifecycleService service = new PluginLifecycleService(
                mock(ApplicationContext.class), runtime, new PluginApplicationContextFactory(),
                controllerRegistrar, webRegistrar, scheduleRegistrar, quiescer,
                capabilityRegistrar, registry, new PluginLifecycleState());
        service.startAll();
        clearInvocations(scheduleRegistrar, streamRegistry, controllerRegistrar);

        assertThat(Thread.interrupted()).isFalse();
        try {
            service.stopAll();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        InOrder order = inOrder(webRegistrar, scheduleRegistrar, secondRequestDrain, controllerRegistrar);
        order.verify(webRegistrar).withdrawRequests(same(secondWebHandle));
        order.verify(scheduleRegistrar).withdraw(any(), eq(secondPublication));
        order.verify(webRegistrar).withdrawRequests(same(firstWebHandle));
        order.verify(scheduleRegistrar).withdraw(any(), eq(firstPublication));
        order.verify(secondRequestDrain).isDrained();
        order.verify(secondRequestDrain).awaitDrained();
        order.verify(secondRequestDrain).isDrained();
        order.verify(secondRequestDrain).awaitDrained();
        order.verify(secondRequestDrain, atLeastOnce()).isDrained();
        order.verify(controllerRegistrar).unregisterControllers("ext-second");
        verify(secondRequestDrain, never()).awaitDrained(anyLong());
        verify(firstRequestDrain, never()).awaitDrained(anyLong());
        verify(secondDrain, never()).awaitDrained(anyLong());
        verify(firstDrain, never()).awaitDrained(anyLong());
        assertThat(service.phase("ext-first")).contains(PluginRuntimePhase.STOPPED);
        assertThat(service.phase("ext-second")).contains(PluginRuntimePhase.STOPPED);
    }

    @Test
    @DisplayName("stopAll 遇到普通清退失败会重试到可排空而不跳过 child 足迹")
    void stopAllRetriesOrdinaryQuiesceFailureUntilSafe() {
        RecordingPlugin plugin = new RecordingPlugin("ext-retry");
        PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, getClass().getClassLoader());
        PluginRegistry registry = mock(PluginRegistry.class);
        when(registry.registeredPlugins()).thenReturn(List.of(registered));
        PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
        when(runtime.inspectContextModules()).thenReturn(List.of());
        PluginWebContributionRegistrar webRegistrar = mock(PluginWebContributionRegistrar.class);
        PluginWebContributionHandle webHandle = mock(PluginWebContributionHandle.class);
        when(webRegistrar.currentHandle(same(registered))).thenReturn(Optional.of(webHandle));
        PluginRuntimeTaskQuiescer quiescer = mock(PluginRuntimeTaskQuiescer.class);
        when(quiescer.withdrawSchedule(any(), any()))
                .thenReturn(new PluginRuntimeTaskQuiescer.QuiesceResult(Optional.empty()));
        doThrow(new IllegalStateException("transient stream close"))
                .doNothing()
                .when(quiescer).quiesceAfterScheduleWithdrawal(eq("ext-retry"), any());
        PluginControllerRegistrar controllerRegistrar = mock(PluginControllerRegistrar.class);
        PluginLifecycleService service = new PluginLifecycleService(
                mock(ApplicationContext.class), runtime, new PluginApplicationContextFactory(),
                controllerRegistrar, webRegistrar, mock(PluginScheduleContributionRegistrar.class),
                quiescer, mock(PluginCapabilityContributionRegistrar.class), registry,
                new PluginLifecycleState());
        service.startAll();

        service.stopAll();

        verify(quiescer, times(2)).quiesceAfterScheduleWithdrawal(eq("ext-retry"), any());
        verify(controllerRegistrar).unregisterControllers("ext-retry");
        verify(webRegistrar).unregister(same(webHandle));
        assertThat(service.phase("ext-retry")).contains(PluginRuntimePhase.STOPPED);
    }

    // ============================ 插件自身 start()/stop() 生命周期组（真实子 context + mock 注册器）============================

    /**
     * 真实父 context + 真实子 context 工厂 + mock 注册器 + 记录型插件的装置：验证「运行期 start/reload 调插件
     * start()、启动期不重复调、start() 失败回滚足迹」。{@code ext-demo} 声明了配置类（建子 context）且有核心注册条目。
     */
    private static final class FirstCloseTrackingPluginStreamRegistry extends PluginStreamRegistry {
        private final CountDownLatch firstCloseReturned = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int closeForPlugin(String pluginId) {
            int closed = super.closeForPlugin(pluginId);
            if (closeCalls.incrementAndGet() == 1) {
                firstCloseReturned.countDown();
            }
            return closed;
        }

        private void awaitFirstCloseReturned() throws InterruptedException {
            assertThat(firstCloseReturned.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static final class ContextHarness implements AutoCloseable {
        final AnnotationConfigApplicationContext parent =
                new AnnotationConfigApplicationContext(ParentCoreConfig.class);
        final PluginControllerRegistrar controllerRegistrar = mock(PluginControllerRegistrar.class);
        final PluginWebContributionRegistrar webRegistrar = mock(PluginWebContributionRegistrar.class);
        final PluginRegistry registry = mock(PluginRegistry.class);
        final PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
        final PluginCapabilityContributionRegistrar capabilityRegistrar =
                mock(PluginCapabilityContributionRegistrar.class);
        final PreparedOwner capabilityPreparation = mock(PreparedOwner.class);
        final ExternalCapabilityPublication capabilityPublication =
                mock(ExternalCapabilityPublication.class);
        final ExternalCapabilityDrain capabilityDrain = mock(ExternalCapabilityDrain.class);
        final PluginScheduleContributionRegistrar scheduleRegistrar =
                mock(PluginScheduleContributionRegistrar.class);
        final PluginStreamRegistry streamRegistry;
        final PluginLifecycleState state = new PluginLifecycleState();
        final RecordingPlugin plugin = new RecordingPlugin("ext-demo");
        final PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, ContextHarness.class.getClassLoader());
        final PluginWebContributionHandle bootWebHandle = mock(PluginWebContributionHandle.class);
        final PluginWebContributionHandle runtimeWebHandle = mock(PluginWebContributionHandle.class);
        final PluginLifecycleService service;

        ContextHarness() {
            this(new PluginStreamRegistry());
        }

        ContextHarness(PluginStreamRegistry streamRegistry) {
            this.streamRegistry = streamRegistry;
            PluginContextModule module = new PluginContextModule(
                    "ext-demo", ContextHarness.class.getClassLoader(), List.of(PluginConfig.class));
            when(runtime.inspectContextModules()).thenReturn(List.of(module));
            when(registry.registeredPlugins()).thenReturn(List.of(registered));
            delegateFeatureCallbacks(registry, registered);
            when(webRegistrar.currentHandle(same(registered))).thenReturn(Optional.of(bootWebHandle));
            PreparedWebContribution preparedWeb = mock(PreparedWebContribution.class);
            when(webRegistrar.prepare(same(registered))).thenReturn(preparedWeb);
            when(webRegistrar.commit(same(preparedWeb))).thenReturn(runtimeWebHandle);
            when(capabilityRegistrar.allocateOwner(
                    eq("ext-demo"), eq("ext-demo"), eq(0L)))
                    .thenReturn(capabilityPreparation);
            when(capabilityRegistrar.publish(same(capabilityPreparation)))
                    .thenReturn(capabilityPublication);
            when(capabilityRegistrar.discardUnpublished(same(capabilityPreparation))).thenReturn(true);
            when(capabilityRegistrar.withdraw(same(capabilityPublication)))
                    .thenReturn(Optional.of(capabilityDrain));
            when(capabilityDrain.awaitDrained(anyLong())).thenReturn(true);
            when(capabilityDrain.isDrained()).thenReturn(true);
            QueueOperationRegistry queueRegistry = new QueueOperationRegistry(List.of());
            service = new PluginLifecycleService(parent, runtime, new PluginApplicationContextFactory(),
                    controllerRegistrar, webRegistrar, scheduleRegistrar,
                    runtimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry),
                    capabilityRegistrar, registry, state);
        }

        @Override
        public void close() {
            parent.close();
        }
    }

    @Test
    @DisplayName("宿主 child-context 操作持有精确 request lease，stop 等回调退出后才关闭 context")
    void servingContextOperationParticipatesInRequestDrain() throws Exception {
        try (ContextHarness h = new ContextHarness()) {
            PluginRequestLeaseRegistry leases = new PluginRequestLeaseRegistry();
            PluginRequestOwner owner = new PluginRequestOwner("ext-demo", 0L, 73L);
            leases.publish(owner);
            CountDownLatch withdrawn = new CountDownLatch(1);
            when(h.webRegistrar.prepareRequestLease(same(h.bootWebHandle)))
                    .thenAnswer(ignored -> leases.prepareLease(owner));
            when(h.webRegistrar.activateRequestLease(same(h.bootWebHandle), any(PluginRequestLease.class)))
                    .thenAnswer(invocation -> leases.activate(invocation.getArgument(1)));
            when(h.webRegistrar.withdrawRequests(same(h.bootWebHandle)))
                    .thenAnswer(ignored -> {
                        PluginRequestGenerationDrain drain = leases.withdraw(owner).orElseThrow();
                        withdrawn.countDown();
                        return Optional.of(drain);
                    });
            h.service.startAll();
            ConfigurableApplicationContext child = h.service.contextFor("ext-demo").orElseThrow();

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> operationFailure = new AtomicReference<>();
            Thread operation = new Thread(() -> {
                try {
                    boolean admitted = h.service.withServingContext("ext-demo", context -> {
                        assertThat(context.isActive()).isTrue();
                        entered.countDown();
                        try {
                            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("serving context operation interrupted", interrupted);
                        }
                    });
                    assertThat(admitted).isTrue();
                } catch (Throwable failure) {
                    operationFailure.set(failure);
                }
            }, "serving-context-operation");
            operation.setDaemon(true);
            AtomicReference<Throwable> stopFailure = new AtomicReference<>();
            Thread stop = new Thread(() -> {
                try {
                    h.service.stop("ext-demo");
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            }, "serving-context-stop");
            stop.setDaemon(true);

            operation.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            stop.start();
            assertThat(withdrawn.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(stop.isAlive()).isTrue();
            assertThat(child.isActive()).isTrue();

            release.countDown();
            operation.join(5_000L);
            stop.join(5_000L);

            assertThat(operation.isAlive()).isFalse();
            assertThat(stop.isAlive()).isFalse();
            assertThat(operationFailure.get()).isNull();
            assertThat(stopFailure.get()).isNull();
            assertThat(child.isActive()).isFalse();
            assertThat(h.service.contextFor("ext-demo")).isEmpty();
            AtomicBoolean invokedAfterStop = new AtomicBoolean();
            assertThat(h.service.withServingContext(
                    "ext-demo", ignored -> invokedAfterStop.set(true))).isFalse();
            assertThat(invokedAfterStop).isFalse();
        }
    }

    @Test
    @DisplayName("宿主 serving lease 激活返回窗的 OOME 与 ThreadDeath 会关闭 exact lease 且不调用 callback")
    void servingContextFatalAfterActivationClosesExactLeaseBeforeCallback() {
        for (Error expected : new Error[]{new OutOfMemoryError("serving-activate"), new ThreadDeath()}) {
            try (ContextHarness h = new ContextHarness()) {
                PluginRequestLeaseRegistry leases = new PluginRequestLeaseRegistry();
                PluginRequestOwner owner = new PluginRequestOwner("ext-demo", 0L, 83L);
                leases.publish(owner);
                AtomicBoolean failAfterActivation = new AtomicBoolean(true);
                when(h.webRegistrar.prepareRequestLease(same(h.bootWebHandle)))
                        .thenAnswer(ignored -> leases.prepareLease(owner));
                when(h.webRegistrar.activateRequestLease(
                        same(h.bootWebHandle), any(top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLease.class)))
                        .thenAnswer(invocation -> {
                            boolean active = leases.activate(invocation.getArgument(1));
                            if (active && failAfterActivation.compareAndSet(true, false)) {
                                throw expected;
                            }
                            return active;
                        });
                h.service.startAll();
                AtomicBoolean callbackInvoked = new AtomicBoolean();

                assertThat(catchThrowable(() -> h.service.withServingContext(
                        "ext-demo", ignored -> callbackInvoked.set(true))))
                        .isSameAs(expected);

                PluginRequestGenerationDrain drain = leases.withdraw(owner).orElseThrow();
                assertThat(callbackInvoked).isFalse();
                assertThat(drain.activeLeaseCount()).isZero();
                assertThat(drain.isDrained()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("首轮推流关闭返回后的迟到注册失败会在最终复核阻断 context 关闭")
    void lateStreamFailureAfterFirstCloseKeepsQuiescedContextForRetry() throws Exception {
        FirstCloseTrackingPluginStreamRegistry streams = new FirstCloseTrackingPluginStreamRegistry();
        try (ContextHarness h = new ContextHarness(streams)) {
            PluginRequestLeaseRegistry leases = new PluginRequestLeaseRegistry();
            PluginRequestOwner owner = new PluginRequestOwner("ext-demo", 0L, 79L);
            leases.publish(owner);
            CountDownLatch withdrawn = new CountDownLatch(1);
            when(h.webRegistrar.prepareRequestLease(same(h.bootWebHandle)))
                    .thenAnswer(ignored -> leases.prepareLease(owner));
            when(h.webRegistrar.activateRequestLease(same(h.bootWebHandle), any(PluginRequestLease.class)))
                    .thenAnswer(invocation -> leases.activate(invocation.getArgument(1)));
            when(h.webRegistrar.withdrawRequests(same(h.bootWebHandle)))
                    .thenAnswer(ignored -> {
                        PluginRequestGenerationDrain drain = leases.withdraw(owner).orElseThrow();
                        withdrawn.countDown();
                        return Optional.of(drain);
                    });
            h.service.startAll();
            ConfigurableApplicationContext child = h.service.contextFor("ext-demo").orElseThrow();

            CountDownLatch requestEntered = new CountDownLatch(1);
            CountDownLatch registerLateStream = new CountDownLatch(1);
            AtomicBoolean failClose = new AtomicBoolean(true);
            AtomicInteger closeAttempts = new AtomicInteger();
            IllegalStateException lateFailure = new IllegalStateException("late stream close failed");
            PluginStream lateStream = () -> {
                closeAttempts.incrementAndGet();
                if (failClose.get()) {
                    throw lateFailure;
                }
            };
            AtomicReference<Throwable> requestFailure = new AtomicReference<>();
            Thread request = new Thread(() -> {
                try {
                    h.service.withServingContext("ext-demo", ignored -> {
                        requestEntered.countDown();
                        try {
                            if (!registerLateStream.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("timed out waiting to register late stream");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("late stream request interrupted", failure);
                        }
                        h.streamRegistry.register("ext-demo", "late-stream", lateStream);
                    });
                } catch (Throwable failure) {
                    requestFailure.set(failure);
                }
            }, "late-stream-request");
            request.setDaemon(true);
            AtomicReference<Throwable> stopFailure = new AtomicReference<>();
            Thread stop = new Thread(() -> {
                try {
                    h.service.stop("ext-demo");
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            }, "late-stream-stop");
            stop.setDaemon(true);

            request.start();
            assertThat(requestEntered.await(5, TimeUnit.SECONDS)).isTrue();
            stop.start();
            assertThat(withdrawn.await(5, TimeUnit.SECONDS)).isTrue();
            streams.awaitFirstCloseReturned();
            assertThat(stop.isAlive()).isTrue();
            assertThat(child.isActive()).isTrue();

            registerLateStream.countDown();
            request.join(5_000L);
            stop.join(5_000L);

            assertThat(request.isAlive()).isFalse();
            assertThat(stop.isAlive()).isFalse();
            assertThat(requestFailure.get()).isSameAs(lateFailure);
            assertThat(stopFailure.get()).isSameAs(lateFailure);
            assertThat(stopFailure.get().getSuppressed()).anySatisfy(suppressed ->
                    assertThat(suppressed).hasMessageContaining("active=1"));
            assertThat(closeAttempts).hasValue(2);
            assertThat(h.streamRegistry.activeStreamCount("ext-demo")).isEqualTo(1);
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);
            assertThat(h.service.contextFor("ext-demo")).containsSame(child);
            assertThat(child.isActive()).isTrue();
            verify(h.controllerRegistrar, never()).unregisterControllers("ext-demo");
            verify(h.webRegistrar, never()).unregister(same(h.bootWebHandle));

            failClose.set(false);
            h.service.stop("ext-demo");

            assertThat(closeAttempts).hasValue(3);
            assertThat(h.streamRegistry.activeStreamCount("ext-demo")).isZero();
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
            assertThat(h.service.contextFor("ext-demo")).isEmpty();
            assertThat(child.isActive()).isFalse();
            verify(h.webRegistrar).unregister(same(h.bootWebHandle));
        }
    }

    /** 真实可变 web/download registry + 真实子 context，用于验证部分清理的可重试生命周期。 */
    private static final class StatefulWebLifecycleHarness implements AutoCloseable {
        final AnnotationConfigApplicationContext parent =
                new AnnotationConfigApplicationContext(ParentCoreConfig.class);
        final StatefulLifecyclePlugin plugin = new StatefulLifecyclePlugin();
        final PluginRegistry registry = new PluginRegistry(List.of());
        final PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, StatefulWebLifecycleHarness.class.getClassLoader(),
                "stateful-owner", 1L);
        final PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
        final PluginControllerRegistrar controllerRegistrar = mock(PluginControllerRegistrar.class);
        final PluginCapabilityContributionRegistrar capabilityRegistrar =
                mock(PluginCapabilityContributionRegistrar.class);
        final PluginScheduleContributionRegistrar scheduleRegistrar =
                mock(PluginScheduleContributionRegistrar.class);
        final FailingLifecycleRouteAccessRegistry routes;
        final StaticResourceRegistry statics;
        final FailingLifecycleWebI18nBundleRegistry i18n;
        final DownloadExtensionRegistry downloads;
        final PluginWebContributionRegistrar webRegistrar;
        final PluginLifecycleState state = new PluginLifecycleState();
        final PluginLifecycleService service;

        StatefulWebLifecycleHarness() {
            registry.register(registered);
            registry.startFeature(registered);
            plugin.startCount = 0;
            routes = new FailingLifecycleRouteAccessRegistry(registry);
            statics = new StaticResourceRegistry(registry);
            i18n = new FailingLifecycleWebI18nBundleRegistry(registry);
            NavigationRegistry navigation = new NavigationRegistry(registry);
            WebUiSlotRegistry slots = new WebUiSlotRegistry(registry);
            UserscriptRegistry userscripts = new UserscriptRegistry(registry);
            ScriptRegistry scripts = new ScriptRegistry(TestI18nBeans.appMessages(), userscripts);
            downloads = new DownloadExtensionRegistry(
                    registry, statics, new PluginOwnedWebAssetValidator(statics));
            webRegistrar = new PluginWebContributionRegistrar(
                    routes, statics, i18n, navigation, slots, userscripts, scripts, registry, downloads);
            PluginContextModule module = new PluginContextModule(
                    plugin.id(), StatefulWebLifecycleHarness.class.getClassLoader(), List.of(PluginConfig.class));
            when(runtime.inspectContextModules()).thenReturn(List.of(module));
            PluginStreamRegistry streamRegistry = new PluginStreamRegistry();
            QueueOperationRegistry queueRegistry = new QueueOperationRegistry(List.of());
            service = new PluginLifecycleService(
                    parent, runtime, new PluginApplicationContextFactory(), controllerRegistrar,
                    webRegistrar, scheduleRegistrar,
                    runtimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry),
                    capabilityRegistrar, registry, state);
        }

        @Override
        public void close() {
            parent.close();
        }
    }

    private static final class FailingLifecycleRouteAccessRegistry extends RouteAccessRegistry {
        private boolean failBeforeUnregister;

        private FailingLifecycleRouteAccessRegistry(PluginRegistry registry) {
            super(registry);
        }

        @Override
        public void unregister(String pluginId) {
            if (failBeforeUnregister) {
                throw new AssertionError("route cleanup failed before snapshot mutation");
            }
            super.unregister(pluginId);
        }

        @Override
        public void unregister(PluginRequestOwner requestOwner) {
            if (failBeforeUnregister) {
                throw new AssertionError("route cleanup failed before snapshot mutation");
            }
            super.unregister(requestOwner);
        }
    }

    private static final class FailingLifecycleWebI18nBundleRegistry extends WebI18nBundleRegistry {
        private boolean failBeforeRegister;

        private FailingLifecycleWebI18nBundleRegistry(PluginRegistry registry) {
            super(registry);
        }

        @Override
        public void register(String pluginId, ClassLoader classLoader,
                             List<I18nContribution> contributions) {
            if (failBeforeRegister) {
                throw new AssertionError("i18n registration failed before snapshot mutation");
            }
            super.register(pluginId, classLoader, contributions);
        }
    }

    @Test
    @DisplayName("boot startAll 不重复调用插件 start()：启动期 start 归 PluginRegistry，本服务只建立服务足迹")
    void bootStartAllDoesNotInvokePluginStart() {
        try (ContextHarness h = new ContextHarness()) {
            h.service.startAll();

            assertThat(h.plugin.startCount).isZero(); // 启动期不由本服务调 start()（PluginRegistry SmartLifecycle 负责）
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STARTED);
            assertThat(h.service.contextCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("启动期缺少精确 web handle 时隔离插件并停止已启动 feature 身份")
    void missingBootWebHandleStopsStartedFeatureIdentity() {
        try (ContextHarness h = new ContextHarness()) {
            when(h.webRegistrar.currentHandle(same(h.registered))).thenReturn(Optional.empty());

            h.service.startAll();

            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
            assertThat(h.service.contextFor("ext-demo")).isEmpty();
            assertThat(h.plugin.stopCount).isEqualTo(1);
            verify(h.registry).stopFeature(same(h.registered));
        }
    }

    @Test
    @DisplayName("stop 后 start 调用插件 start()：运行期重启恢复插件自身生命周期（修复 start/stop 不对称）")
    void runtimeStartInvokesPluginStartAfterStop() {
        try (ContextHarness h = new ContextHarness()) {
            h.service.startAll();

            h.service.stop("ext-demo");
            assertThat(h.plugin.stopCount).isEqualTo(1);
            assertThat(h.plugin.startCount).isZero();

            h.service.start("ext-demo");
            assertThat(h.plugin.startCount).isEqualTo(1); // 运行期 start 重新调插件 start()
            assertThat(h.plugin.stopCount).isEqualTo(1);
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STARTED);
        }
    }

    @Test
    @DisplayName("reload：先 stop 再 start，插件 stop() / start() 各调用一次")
    void reloadStopsThenStartsPlugin() {
        try (ContextHarness h = new ContextHarness()) {
            h.service.startAll();

            h.service.reload("ext-demo");

            assertThat(h.plugin.stopCount).isEqualTo(1);
            assertThat(h.plugin.startCount).isEqualTo(1);
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STARTED);
        }
    }

    @Test
    @DisplayName("运行期 start 时插件 start() 抛异常：回滚 controller / web / 子 context 足迹，落 STOPPED、不进入 STARTED")
    void runtimeStartFailureRollsBackFootprint() {
        try (ContextHarness h = new ContextHarness()) {
            h.service.startAll();
            h.service.stop("ext-demo");
            h.plugin.failStart = true;
            clearInvocations(h.controllerRegistrar, h.webRegistrar); // 只校验本次 start + 回滚的注册器交互

            h.service.start("ext-demo");

            assertThat(h.plugin.startCount).isEqualTo(1);                                  // start() 被调过（随即抛异常）
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);  // 不进入 STARTED
            assertThat(h.service.contextFor("ext-demo")).isEmpty();                        // 子 context 已关闭回收
            assertThat(h.service.contextCount()).isZero();
            verify(h.controllerRegistrar).unregisterControllers("ext-demo");               // controller 足迹回滚
            verify(h.webRegistrar, never()).commit(any(PreparedWebContribution.class));  // start 未就绪前不发布 web serving
        }
    }

    @Test
    @DisplayName("运行期 start 抛断言错误时同样隔离并精确回滚本轮服务足迹")
    void runtimeStartAssertionErrorRollsBackFootprint() {
        try (ContextHarness h = new ContextHarness()) {
            h.service.startAll();
            h.service.stop("ext-demo");
            h.plugin.failStartWithError = true;
            clearInvocations(h.controllerRegistrar, h.webRegistrar);

            h.service.start("ext-demo");

            assertThat(h.plugin.startCount).isEqualTo(1);
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
            assertThat(h.service.contextFor("ext-demo")).isEmpty();
            verify(h.controllerRegistrar).unregisterControllers("ext-demo");
            verify(h.webRegistrar, never()).commit(any(PreparedWebContribution.class));
        }
    }

    @Test
    @DisplayName("能力准备失败时生命周期关闭子 context 且不发布半成品")
    void capabilityRegistrationFailureRetriesIdempotentUnregister() {
        try (ContextHarness h = new ContextHarness()) {
            doThrow(new IllegalStateException("capability failed"))
                    .when(h.capabilityRegistrar).prepareInto(
                            same(h.capabilityPreparation), any());

            h.service.startAll();

            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
            assertThat(h.service.contextFor("ext-demo")).isEmpty();
            verify(h.capabilityRegistrar).allocateOwner(
                    eq("ext-demo"), eq("ext-demo"), eq(0L));
            verify(h.capabilityRegistrar).prepareInto(
                    same(h.capabilityPreparation), any());
            verify(h.capabilityRegistrar, never()).publish(any());
        }
    }

    @Test
    @DisplayName("运行期 capability admission 在 schedule 与完整 serving 就绪后最后发布")
    void runtimeSchedulePublicationIsFinalBringUpStep() {
        try (ContextHarness h = new ContextHarness()) {
            when(h.scheduleRegistrar.register(any(), eq(h.registered), any()))
                    .thenReturn(Optional.empty())
                    .thenAnswer(invocation -> {
                        assertThat(h.plugin.startCount).isEqualTo(1);
                        assertThat(h.service.contextFor("ext-demo")).isPresent();
                        verify(h.capabilityRegistrar).allocateOwner(
                                eq("ext-demo"), eq("ext-demo"), eq(0L));
                        verify(h.capabilityRegistrar).prepareInto(
                                same(h.capabilityPreparation), any());
                        verify(h.capabilityRegistrar, never()).publish(any());
                        verify(h.controllerRegistrar).registerControllers(
                                eq("ext-demo"), any(), any(PreparedWebContribution.class));
                        return Optional.empty();
                    });
            h.service.startAll();
            h.service.stop("ext-demo");
            clearInvocations(
                    h.capabilityRegistrar, h.controllerRegistrar, h.webRegistrar, h.scheduleRegistrar);

            h.service.start("ext-demo");

            InOrder order = inOrder(
                    h.capabilityRegistrar, h.controllerRegistrar, h.webRegistrar, h.scheduleRegistrar);
            order.verify(h.capabilityRegistrar).allocateOwner(
                    eq("ext-demo"), eq("ext-demo"), eq(0L));
            order.verify(h.capabilityRegistrar).prepareInto(
                    same(h.capabilityPreparation), any());
            order.verify(h.webRegistrar).prepare(same(h.registered));
            order.verify(h.controllerRegistrar).registerControllers(
                    eq("ext-demo"), any(), any(PreparedWebContribution.class));
            order.verify(h.webRegistrar).commit(any(PreparedWebContribution.class));
            order.verify(h.scheduleRegistrar).register(any(), eq(h.registered), any());
            order.verify(h.capabilityRegistrar).publish(same(h.capabilityPreparation));
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STARTED);
        }
    }

    @Test
    @DisplayName("启动期足迹失败经 PluginRegistry 停止已启动的精确身份")
    void bootSchedulePublicationFailureStopsStartedFeatureIdentity() {
        try (ContextHarness h = new ContextHarness()) {
            when(h.scheduleRegistrar.register(any(), eq(h.registered), any()))
                    .thenThrow(new IllegalStateException("publish failed"));

            h.service.startAll();

            assertThat(h.plugin.startCount).isZero();
            assertThat(h.plugin.stopCount).isEqualTo(1);
            verify(h.registry).stopFeature(same(h.registered));
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
            assertThat(h.service.contextFor("ext-demo")).isEmpty();
            verify(h.capabilityRegistrar).discardUnpublished(same(h.capabilityPreparation));
            verify(h.controllerRegistrar).unregisterControllers("ext-demo");
        }
    }

    @Test
    @DisplayName("运行期插件 start 成功但最终 schedule publication 失败时对称调用 stop 并回滚足迹")
    void runtimeSchedulePublicationFailureStopsStartedPlugin() {
        try (ContextHarness h = new ContextHarness()) {
            h.service.startAll();
            h.service.stop("ext-demo");
            clearInvocations(
                    h.capabilityRegistrar, h.controllerRegistrar, h.webRegistrar);
            when(h.scheduleRegistrar.register(any(), eq(h.registered), any()))
                    .thenThrow(new AssertionError("publish failed"));

            h.service.start("ext-demo");

            assertThat(h.plugin.startCount).isEqualTo(1);
            assertThat(h.plugin.stopCount).isEqualTo(2);
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
            assertThat(h.service.contextFor("ext-demo")).isEmpty();
            verify(h.capabilityRegistrar).discardUnpublished(same(h.capabilityPreparation));
            verify(h.controllerRegistrar).unregisterControllers("ext-demo");
            verify(h.webRegistrar).unregister(same(h.runtimeWebHandle));
        }
    }

    @Test
    @DisplayName("推流关闭失败残留时 start 回滚保持 QUIESCED/context，重试 stop 清零后才关闭")
    void streamCleanupFailureKeepsContextForRetry() {
        try (ContextHarness h = new ContextHarness()) {
            h.service.startAll();
            h.service.stop("ext-demo");
            AtomicBoolean failClose = new AtomicBoolean(true);
            assertThatThrownBy(() -> h.streamRegistry.register("ext-demo", "late-stream", () -> {
                if (failClose.get()) {
                    throw new IllegalStateException("late stream close failed");
                }
            })).isInstanceOf(IllegalStateException.class);

            assertThatThrownBy(() -> h.service.start("ext-demo"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("late stream close failed");

            ConfigurableApplicationContext retained = h.service.contextFor("ext-demo").orElseThrow();
            assertThat(retained.isActive()).isTrue();
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);
            assertThat(h.streamRegistry.activeStreamCount("ext-demo")).isEqualTo(1);
            assertThat(h.plugin.startCount).isEqualTo(1);
            assertThat(h.plugin.stopCount).isEqualTo(1);

            failClose.set(false);
            h.service.stop("ext-demo");

            assertThat(h.streamRegistry.activeStreamCount("ext-demo")).isZero();
            assertThat(retained.isActive()).isFalse();
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
            assertThat(h.plugin.stopCount).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("运行期 start 在插件尚未就绪时不发布 route/static/download 快照")
    void runtimeStartPublishesWebOnlyAfterPluginIsReady() throws Exception {
        try (StatefulWebLifecycleHarness h = new StatefulWebLifecycleHarness()) {
            h.service.startAll();
            h.service.stop(h.plugin.id());
            CountDownLatch startEntered = new CountDownLatch(1);
            CountDownLatch releaseStart = new CountDownLatch(1);
            h.plugin.startEntered = startEntered;
            h.plugin.releaseStart = releaseStart;
            AtomicReference<Throwable> startFailure = new AtomicReference<>();
            Thread start = new Thread(() -> {
                try {
                    h.service.start(h.plugin.id());
                } catch (Throwable failure) {
                    startFailure.set(failure);
                }
            }, "stateful-plugin-start");
            start.start();
            assertThat(startEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(h.service.phase(h.plugin.id())).contains(PluginRuntimePhase.STOPPED);
            assertThat(h.routes.routes()).noneMatch(route -> route.pluginId().equals(h.plugin.id()));
            assertThat(h.statics.resources()).noneMatch(resource -> resource.pluginId().equals(h.plugin.id()));
            assertThat(h.downloads.snapshot().downloadTypes()).isEmpty();
            assertThat(h.webRegistrar.currentHandle(h.registered)).isEmpty();

            releaseStart.countDown();
            start.join(5000);
            assertThat(start.isAlive()).isFalse();
            assertThat(startFailure.get()).isNull();
            assertThat(h.service.phase(h.plugin.id())).contains(PluginRuntimePhase.STARTED);
            assertThat(h.routes.routes()).anyMatch(route -> route.pluginId().equals(h.plugin.id()));
            assertThat(h.statics.resources()).anyMatch(resource -> resource.pluginId().equals(h.plugin.id()));
            assertThat(h.downloads.snapshot().downloadTypes()).singleElement().satisfies(type ->
                    assertThat(type.descriptor().type()).isEqualTo("stateful-type"));
            h.plugin.startEntered = null;
            h.plugin.releaseStart = null;
            h.service.stop(h.plugin.id());
        }
    }

    @Test
    @DisplayName("最终 schedule 发布失败且 web registry 未删除时保留 QUIESCED/context，重试 stop 后用新句柄启动")
    void scheduleFailureWithStatefulWebCleanupRemainsRetryable() {
        try (StatefulWebLifecycleHarness h = new StatefulWebLifecycleHarness()) {
            h.service.startAll();
            h.service.stop(h.plugin.id());
            when(h.scheduleRegistrar.register(any(), same(h.registered), any()))
                    .thenThrow(new IllegalStateException("schedule final publish failed"))
                    .thenReturn(Optional.empty());
            h.routes.failBeforeUnregister = true;

            assertThatThrownBy(() -> h.service.start(h.plugin.id()))
                    .isInstanceOf(PluginLifecycleException.class)
                    .hasMessageContaining("web serving remains current")
                    .hasNoCause();

            PluginWebContributionHandle failedHandle =
                    h.webRegistrar.currentHandle(h.registered).orElseThrow();
            ConfigurableApplicationContext failedContext =
                    h.service.contextFor(h.plugin.id()).orElseThrow();
            assertThat(h.service.phase(h.plugin.id())).contains(PluginRuntimePhase.QUIESCED);
            assertThat(failedContext.isActive()).isTrue();
            assertThat(h.plugin.startCount).isEqualTo(1);
            assertThat(h.plugin.stopCount).isEqualTo(1);
            assertThat(h.routes.routes()).anyMatch(route -> route.pluginId().equals(h.plugin.id()));
            assertThat(h.downloads.snapshot().downloadTypes()).isEmpty();

            clearInvocations(h.controllerRegistrar, h.capabilityRegistrar);
            assertThatThrownBy(() -> h.service.unload(h.plugin.id()))
                    .isInstanceOf(PluginLifecycleException.class)
                    .hasMessageContaining("web serving remains current");
            verify(h.controllerRegistrar, never()).unregisterControllers(h.plugin.id());
            verify(h.capabilityRegistrar, never()).retireDrained(any());
            assertThat(h.registry.registeredPlugins()).anyMatch(current -> current == h.registered);
            assertThat(h.service.phase(h.plugin.id())).contains(PluginRuntimePhase.QUIESCED);
            assertThat(failedContext.isActive()).isTrue();
            assertThat(h.plugin.stopCount).isEqualTo(1);

            h.routes.failBeforeUnregister = false;
            h.service.stop(h.plugin.id());
            assertThat(h.service.phase(h.plugin.id())).contains(PluginRuntimePhase.STOPPED);
            assertThat(h.service.contextFor(h.plugin.id())).isEmpty();
            assertThat(h.webRegistrar.isCurrent(failedHandle)).isFalse();
            assertThat(h.plugin.stopCount).isEqualTo(2);

            h.service.start(h.plugin.id());
            PluginWebContributionHandle restartedHandle =
                    h.webRegistrar.currentHandle(h.registered).orElseThrow();
            assertThat(restartedHandle).isNotSameAs(failedHandle);
            assertThat(restartedHandle.servingId()).isGreaterThan(failedHandle.servingId());
            assertThat(h.service.phase(h.plugin.id())).contains(PluginRuntimePhase.STARTED);
            h.service.stop(h.plugin.id());
        }
    }

    @Test
    @DisplayName("正向 web 注册回滚残留时 provisional handle 落 QUIESCED 并阻断 unload，故障解除后可清零重启")
    void forwardWebRegistrationCleanupFailureRemainsRetryable() {
        try (StatefulWebLifecycleHarness h = new StatefulWebLifecycleHarness()) {
            h.service.startAll();
            h.service.stop(h.plugin.id());
            h.routes.failBeforeUnregister = true;
            h.i18n.failBeforeRegister = true;

            assertThatThrownBy(() -> h.service.start(h.plugin.id()))
                    .isInstanceOf(PluginLifecycleException.class)
                    .hasMessageContaining("registration cleanup remains pending")
                    .hasNoCause();

            PluginWebContributionHandle provisional =
                    h.webRegistrar.currentHandle(h.registered).orElseThrow();
            ConfigurableApplicationContext failedContext =
                    h.service.contextFor(h.plugin.id()).orElseThrow();
            assertThat(h.service.phase(h.plugin.id())).contains(PluginRuntimePhase.QUIESCED);
            assertThat(failedContext.isActive()).isTrue();
            assertThat(h.plugin.startCount).isEqualTo(1);
            assertThat(h.plugin.stopCount).isEqualTo(1);
            assertThat(h.routes.routes()).anyMatch(route -> route.pluginId().equals(h.plugin.id()));
            assertThat(h.downloads.snapshot().downloadTypes()).isEmpty();

            clearInvocations(h.controllerRegistrar, h.capabilityRegistrar);
            assertThatThrownBy(() -> h.service.unload(h.plugin.id()))
                    .isInstanceOf(PluginLifecycleException.class)
                    .hasMessageContaining("web serving remains current");
            verify(h.controllerRegistrar, never()).unregisterControllers(h.plugin.id());
            verify(h.capabilityRegistrar, never()).retireDrained(any());
            assertThat(h.registry.registeredPlugins()).anyMatch(current -> current == h.registered);
            assertThat(failedContext.isActive()).isTrue();

            h.routes.failBeforeUnregister = false;
            h.i18n.failBeforeRegister = false;
            h.service.stop(h.plugin.id());
            assertThat(h.service.phase(h.plugin.id())).contains(PluginRuntimePhase.STOPPED);
            assertThat(h.webRegistrar.isCurrent(provisional)).isFalse();
            assertThat(h.service.contextFor(h.plugin.id())).isEmpty();

            h.service.start(h.plugin.id());
            PluginWebContributionHandle restarted =
                    h.webRegistrar.currentHandle(h.registered).orElseThrow();
            assertThat(restarted).isNotSameAs(provisional);
            assertThat(h.service.phase(h.plugin.id())).contains(PluginRuntimePhase.STARTED);
            h.service.stop(h.plugin.id());
        }
    }

    // ============================ schedule 贡献热插拔组（真实子 context + 真实调度注册中心）============================

    @Test
    @DisplayName("startAll 注册外置插件 schedule 描述符与执行器：规范 type、旧别名与作品类型均可解析")
    void startAllRegistersScheduleDescriptorAndExecutors() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();

            assertThat(h.capabilityRegistry.resolveSourceDescriptor("ext-source")).isPresent();
            assertThat(h.capabilityRegistry.resolveSourceDescriptor("EXT_SOURCE"))
                    .isEqualTo(h.capabilityRegistry.resolveSourceDescriptor("ext-source"));
            assertThat(h.capabilityRegistry.resolveSourceExecutor("EXT_SOURCE"))
                    .isEqualTo(h.capabilityRegistry.resolveSourceExecutor("ext-source"));
            assertThat(h.capabilityRegistry.resolveWorkExecutor("ext-kind")).isPresent();
            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.STARTED);
        }
    }

    @Test
    @DisplayName("stop 撤回 schedule publication：来源与执行器均不再解析，残留任务数据保留")
    void stopUnregistersScheduleContributions() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();

            h.service.stop("ext-sched");

            assertThat(h.capabilityRegistry.resolveSourceDescriptor("ext-source")).isEmpty();
            assertThat(h.capabilityRegistry.resolveSourceExecutor("ext-source")).isEmpty();
            assertThat(h.capabilityRegistry.resolveWorkExecutor("ext-kind")).isEmpty();
            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.STOPPED);
        }
    }

    @Test
    @DisplayName("unload 撤回 schedule publication 并从核心注册中心移除")
    void unloadUnregistersScheduleContributions() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();

            h.service.unload("ext-sched");

            assertThat(h.capabilityRegistry.resolveSourceDescriptor("ext-source")).isEmpty();
            assertThat(h.capabilityRegistry.resolveSourceExecutor("ext-source")).isEmpty();
            assertThat(h.capabilityRegistry.resolveWorkExecutor("ext-kind")).isEmpty();
            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.UNLOADED);
        }
    }

    @Test
    @DisplayName("reload 后 schedule 来源 + 执行器恢复：规范 type / 作品类型再次可解析（来源恢复路径）")
    void reloadRestoresScheduleContributions() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();

            h.service.reload("ext-sched");

            assertThat(h.capabilityRegistry.resolveSourceDescriptor("ext-source")).isPresent();
            assertThat(h.capabilityRegistry.resolveSourceExecutor("ext-source")).isPresent();
            assertThat(h.capabilityRegistry.resolveWorkExecutor("ext-kind")).isPresent();
            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.STARTED);
        }
    }

    @Test
    @DisplayName("stop → start 往返：现代来源与作品执行器缺失后恢复")
    void stopThenStartRecoversExecutorResolution() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();
            assertThat(h.capabilityRegistry.resolveWorkExecutor("ext-kind")).isPresent();

            h.service.stop("ext-sched");
            assertThat(h.capabilityRegistry.resolveSourceExecutor("ext-source")).isEmpty();
            assertThat(h.capabilityRegistry.resolveWorkExecutor("ext-kind")).isEmpty();

            h.service.start("ext-sched");
            assertThat(h.capabilityRegistry.resolveSourceExecutor("ext-source")).isPresent();
            assertThat(h.capabilityRegistry.resolveWorkExecutor("ext-kind")).isPresent();
            assertThat(h.capabilityRegistry.resolveSourceDescriptor("ext-source")).isPresent();
        }
    }

    @Test
    @DisplayName("schedule 注册失败（作品类型冲突）：回滚 web/controller/子 context，落 STOPPED 且既有执行器不污染")
    void scheduleRegisterFailureRollsBackFootprint() {
        try (ScheduleHarness h = new ScheduleHarness(workExecutor("ext-kind"))) {
            h.service.startAll();

            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.STOPPED);
            assertThat(h.service.contextFor("ext-sched")).isEmpty();
            assertThat(h.capabilityRegistry.resolveSourceDescriptor("ext-source")).isEmpty();
            assertThat(h.capabilityRegistry.resolveSourceExecutor("ext-source")).isEmpty();
            assertThat(h.capabilityRegistry.resolveWorkExecutor("ext-kind")).isPresent();
            verify(h.controllerRegistrar).unregisterControllers("ext-sched");
            verify(h.webRegistrar).unregister(same(h.bootWebHandle));
        }
    }

    @Test
    @DisplayName("运行期 start 时插件 start() 抛异常：不发布 schedule 贡献并回滚足迹，落 STOPPED")
    void pluginStartFailureRollsBackScheduleContributions() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();
            h.service.stop("ext-sched");
            h.plugin.failStart = true;

            h.service.start("ext-sched"); // 足迹重建期间 plugin.start() 抛异常，最终 schedule publication 尚未发生

            assertThat(h.plugin.startCount).isEqualTo(1);
            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.STOPPED);
            assertThat(h.capabilityRegistry.resolveSourceDescriptor("ext-source")).isEmpty();
            assertThat(h.capabilityRegistry.resolveSourceExecutor("ext-source")).isEmpty();
            assertThat(h.capabilityRegistry.resolveWorkExecutor("ext-kind")).isEmpty();
        }
    }

    @Test
    @DisplayName("quiesce 撤回 schedule publication：解析均落空且子 context 保持活动")
    void quiesceUnregistersScheduleContributions() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();
            assertThat(h.capabilityRegistry.resolveSourceDescriptor("ext-source")).isPresent();
            assertThat(h.capabilityRegistry.resolveSourceExecutor("ext-source")).isPresent();
            assertThat(h.capabilityRegistry.resolveWorkExecutor("ext-kind")).isPresent();

            h.service.quiesce("ext-sched");

            // publication 随 quiesce 精确撤回 → ScheduleExecutor 解析不到 → 残留任务数据保留
            //（「解析落空 → SOURCE_UNAVAILABLE 且不读 cookie / 不发现 / 不派发 / 不删数据」链路由 ScheduleExecutorSourceResolutionTest 钉死）。
            assertThat(h.capabilityRegistry.resolveSourceDescriptor("ext-source")).isEmpty();
            assertThat(h.capabilityRegistry.resolveSourceDescriptor("EXT_SOURCE")).isEmpty();
            assertThat(h.capabilityRegistry.resolveSourceExecutor("EXT_SOURCE")).isEmpty();
            assertThat(h.capabilityRegistry.resolveWorkExecutor("ext-kind")).isEmpty();
            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.QUIESCED);
            // quiesce 仅停新派发 + 清退在途、不拆服务足迹：子 context 仍在（待 stop 才关闭）。
            assertThat(h.service.contextFor("ext-sched")).isPresent();
        }
    }

    @Test
    @DisplayName("quiesce 后 stop 复用既有 drain、不二次撤回，归零后关闭子 context")
    void quiesceThenStopKeepsScheduleUnregisteredIdempotently() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();

            h.service.quiesce("ext-sched");
            assertThat(h.capabilityRegistry.resolveSourceDescriptor("ext-source")).isEmpty();

            // stop after quiesce：复用 quiesce 已取得的 generation drain，不二次撤回 publication。
            h.service.stop("ext-sched");

            assertThat(h.capabilityRegistry.resolveSourceDescriptor("ext-source")).isEmpty();
            assertThat(h.capabilityRegistry.resolveSourceExecutor("ext-source")).isEmpty();
            assertThat(h.capabilityRegistry.resolveWorkExecutor("ext-kind")).isEmpty();
            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.STOPPED);
            assertThat(h.service.contextFor("ext-sched")).isEmpty(); // stop 才关闭子 context
        }
    }

    @Test
    @DisplayName("reload from QUIESCED：stop 后 start，schedule 来源 + 执行器恢复、阶段回 STARTED")
    void reloadFromQuiescedRestoresScheduleContributions() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();
            h.service.quiesce("ext-sched");
            assertThat(h.capabilityRegistry.resolveSourceDescriptor("ext-source")).isEmpty();
            assertThat(h.capabilityRegistry.resolveSourceExecutor("ext-source")).isEmpty();
            assertThat(h.capabilityRegistry.resolveWorkExecutor("ext-kind")).isEmpty();

            h.service.reload("ext-sched"); // QUIESCED → stop → start

            assertThat(h.capabilityRegistry.resolveSourceDescriptor("ext-source")).isPresent();
            assertThat(h.capabilityRegistry.resolveSourceDescriptor("EXT_SOURCE"))
                    .isEqualTo(h.capabilityRegistry.resolveSourceDescriptor("ext-source"));
            assertThat(h.capabilityRegistry.resolveSourceExecutor("EXT_SOURCE"))
                    .isEqualTo(h.capabilityRegistry.resolveSourceExecutor("ext-source"));
            assertThat(h.capabilityRegistry.resolveWorkExecutor("ext-kind")).isPresent();
            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.STARTED);
        }
    }

    // ============================ 夹具 ============================

    private static PluginLifecycleService realService(ApplicationContext parent, List<PluginContextModule> modules) {
        PluginRegistry pluginRegistry = new PluginRegistry(List.of());
        PluginScheduleContributionRegistrar scheduleRegistrar = emptyScheduleRegistrar(pluginRegistry);
        PluginStreamRegistry streamRegistry = new PluginStreamRegistry();
        QueueOperationRegistry queueRegistry = new QueueOperationRegistry(List.of());
        return new PluginLifecycleService(parent, runtimeReturning(modules), new PluginApplicationContextFactory(),
                emptyControllerRegistrar(), emptyWebRegistrar(), scheduleRegistrar,
                runtimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry), capabilityRegistrar(),
                pluginRegistry, new PluginLifecycleState());
    }

    private static PluginRuntimeTaskQuiescer runtimeTaskQuiescer(
            PluginScheduleContributionRegistrar scheduleRegistrar,
            PluginStreamRegistry streamRegistry,
            QueueOperationRegistry queueRegistry) {
        return new PluginRuntimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry);
    }

    /** 模拟 phase 0 已成功启动精确身份，并让 mock 保持与 PluginRegistry 相同的成功后状态转换语义。 */
    private static void delegateFeatureCallbacks(
            PluginRegistry registry, PluginRegistry.RegisteredPlugin registered) {
        AtomicBoolean started = new AtomicBoolean(true);
        when(registry.featureStarted(same(registered))).thenAnswer(ignored -> started.get());
        when(registry.startFeature(same(registered))).thenAnswer(ignored -> {
            if (started.get()) {
                return false;
            }
            registered.plugin().start();
            started.set(true);
            return true;
        });
        when(registry.stopFeature(same(registered))).thenAnswer(ignored -> {
            if (!started.get()) {
                return false;
            }
            registered.plugin().stop();
            started.set(false);
            return true;
        });
    }

    private static PluginScheduleContributionRegistrar emptyScheduleRegistrar(PluginRegistry pluginRegistry) {
        return ScheduleCapabilityRegistryTestAccess.registrar(
                new ScheduleCapabilityRegistry(), noOpMigrationService(),
                pluginRegistry);
    }

    private static LegacyScheduledTaskMigrationService noOpMigrationService() {
        return (reservation, adapter) ->
                new LegacyScheduledTaskMigrationService.OwnerMigrationReport("unused", 0, 0, 0, 0);
    }

    private static PluginCapabilityContributionRegistrar capabilityRegistrar() {
        return new PluginCapabilityContributionRegistrar(List.of());
    }

    private static PluginRuntimeManager runtimeReturning(List<PluginContextModule> modules) {
        return new PluginRuntimeManager(Path.of("target/no-such-plugins-dir")) {
            @Override
            public List<PluginContextModule> inspectContextModules() {
                return modules;
            }
        };
    }

    private static PluginControllerRegistrar emptyControllerRegistrar() {
        return new PluginControllerRegistrar(new PluginAwareRequestMappingHandlerMapping(),
                new RouteAccessRegistry(new PluginRegistry(List.of())));
    }

    private static PluginWebContributionRegistrar emptyWebRegistrar() {
        PluginRegistry empty = new PluginRegistry(List.of());
        UserscriptRegistry userscripts = new UserscriptRegistry(empty);
        ScriptRegistry scripts = new ScriptRegistry(TestI18nBeans.appMessages(), userscripts);
        return new PluginWebContributionRegistrar(
                new RouteAccessRegistry(empty), new StaticResourceRegistry(empty),
                new WebI18nBundleRegistry(empty), new NavigationRegistry(empty),
                new WebUiSlotRegistry(empty), userscripts, scripts);
    }

    /** 记录 start() / stop() 调用次数的功能插件夹具（验证生命周期被调、幂等，{@code failStart} 可令 start() 抛异常）。 */
    private static final class RecordingPlugin implements PixivFeaturePlugin {
        private final String id;
        private int startCount;
        private int stopCount;
        private boolean failStart;
        private boolean failStartWithError;
        private boolean failDownloadTypesWithError;
        private String queueType; // 非空时声明对应作品类型（验证 quiesce / 卸载时排空其在途队列）
        private List<ScheduledSourceDescriptor> scheduledSourceDescriptors = List.of();

        RecordingPlugin(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public List<DownloadTypeDescriptor> downloadTypes() {
            if (failDownloadTypesWithError) {
                throw new AssertionError("plugin-private-download-types");
            }
            return queueType == null ? List.of()
                    : List.of(TestDownloadTypeDescriptors.create(
                            queueType, id, "label", 10, downloadModuleUrl()));
        }

        @Override
        public List<StaticResourceContribution> staticResources() {
            return queueType == null ? List.of() : List.of(new StaticResourceContribution(
                    id, "classpath:/test-download/", downloadPublicPrefix()));
        }

        private String downloadPublicPrefix() {
            return "/" + id + "-download/";
        }

        private String downloadModuleUrl() {
            return downloadPublicPrefix() + "module.js";
        }

        @Override
        public List<ScheduledSourceDescriptor> scheduledSourceDescriptors() {
            return scheduledSourceDescriptors;
        }

        @Override
        public String displayName() {
            return id + ".label";
        }

        @Override
        public String description() {
            return id + ".summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }

        @Override
        public void start() {
            startCount++;
            if (failStart) {
                throw new RuntimeException("boom-start");
            }
            if (failStartWithError) {
                throw new AssertionError("boom-start-error");
            }
        }

        @Override
        public void stop() {
            stopCount++;
        }
    }

    private static final class StatefulLifecyclePlugin implements PixivFeaturePlugin {
        private int startCount;
        private int stopCount;
        private volatile CountDownLatch startEntered;
        private volatile CountDownLatch releaseStart;

        @Override public String id() { return "stateful-owner"; }
        @Override public String displayName() { return "stateful-owner.name"; }
        @Override public String description() { return "stateful-owner.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }

        @Override
        public List<WebRouteContribution> routes() {
            return List.of(WebRouteContribution.admin("/stateful-owner/**"));
        }

        @Override
        public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution(
                    id(), "classpath:/test-download/", "/stateful-owner/"));
        }

        @Override
        public List<I18nContribution> i18n() {
            return List.of(new I18nContribution("stateful-owner", "i18n.web.stateful-owner"));
        }

        @Override
        public List<DownloadTypeDescriptor> downloadTypes() {
            return List.of(TestDownloadTypeDescriptors.create(
                    "stateful-type", id(), "label", 10,
                    "/stateful-owner/module.js"));
        }

        @Override
        public void start() {
            startCount++;
            CountDownLatch entered = startEntered;
            CountDownLatch release = releaseStart;
            if (entered == null || release == null) {
                return;
            }
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release plugin start");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("plugin start interrupted");
            }
        }

        @Override
        public void stop() {
            stopCount++;
        }
    }

    /**
     * 记录生命周期取消与普通 clear 的队列操作夹具；真实任务经 {@link QueueTaskTracker} wrapper 运行，
     * drain 只有在 wrapper 的 finally 归还凭据后才归零。
     */
    private static final class RecordingQueueOperations implements QueueOperations {
        private final String type;
        private final top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker tracker;
        int clearAllCount;
        boolean failClearAllWithError;

        RecordingQueueOperations(String type) {
            this.type = type;
            this.tracker = new top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker(type);
        }

        @Override
        public String queueType() {
            return type;
        }

        @Override
        public top.sywyar.pixivdownload.plugin.api.download.queue.QueueGenerationDrain prepareQuiesce(
                String registeredQueueType) {
            return tracker.prepareQuiesce();
        }

        @Override
        public void cancelQuiescedTasks() {
            tracker.cancelQuiescedTasks();
            clearAll();
        }

        @Override
        public int clearAll() {
            clearAllCount++;
            if (failClearAllWithError) {
                throw new AssertionError("plugin-private-clear-all");
            }
            return 3;
        }

        @Override
        public int clearForOwner(String ownerUuid) {
            return 0;
        }

        QueueTaskTracker.Task queuedTask(Runnable delegate, Runnable cancellation) {
            QueueTaskTracker.Task task = tracker.prepareQueued("test-owner");
            task.onCancellation(cancellation);
            task.bind(delegate);
            return task;
        }
    }

    /** 记录关闭次数的推流夹具（验证 quiesce / 卸载时被 closeForPlugin 关闭）。 */
    private static final class RecordingStream implements PluginStream {
        int closedCount;

        @Override
        public void closeUnavailable() {
            closedCount++;
        }
    }

    interface CoreApiService {
        String describe();
    }

    @Configuration
    static class ParentCoreConfig {
        @Bean
        CoreApiService coreApiService() {
            return () -> "core";
        }
    }

    static final class PluginBean {
        private final CoreApiService coreService;

        PluginBean(CoreApiService coreService) {
            this.coreService = coreService;
        }

        CoreApiService coreService() {
            return coreService;
        }
    }

    @Configuration
    static class PluginConfig {
        @Bean
        PluginBean pluginBean(CoreApiService coreService) {
            return new PluginBean(coreService);
        }
    }

    /** 依赖一个父 context 不提供的类型，refresh 时无法满足 → 子 context 建立失败（验证失败隔离）。 */
    @Configuration
    static class BrokenPluginConfig {
        @Bean
        PluginBean brokenBean(MissingDependency missing) {
            return new PluginBean(null);
        }
    }

    interface MissingDependency {
    }

    // --- schedule 贡献夹具 ---

    /**
     * 真实父 + 真实子 context + 真实调度注册中心 + mock web/controller 注册器的装置：验证 schedule 来源 / 执行器随
     * 插件 start/stop/unload/reload 热插拔与注册失败回滚。{@code ext-sched} 贡献一个来源描述符（规范
     * {@code ext-source} + 旧别名 {@code EXT_SOURCE}），其子 context 含匹配的来源执行器和
     * {@code workType=ext-kind} 作品执行器；可注入预置作品执行器制造 work type 冲突。
     */
    private static final class ScheduleHarness implements AutoCloseable {
        final AnnotationConfigApplicationContext parent =
                new AnnotationConfigApplicationContext(ParentCoreConfig.class);
        final PluginControllerRegistrar controllerRegistrar = mock(PluginControllerRegistrar.class);
        final PluginWebContributionRegistrar webRegistrar = mock(PluginWebContributionRegistrar.class);
        final PluginRegistry registry = new PluginRegistry(List.of());
        final PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
        final PluginLifecycleState state = new PluginLifecycleState();
        final RecordingPlugin plugin = new RecordingPlugin("ext-sched");
        final PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, ScheduleHarness.class.getClassLoader());
        final PluginWebContributionHandle bootWebHandle = mock(PluginWebContributionHandle.class);
        final PluginWebContributionHandle runtimeWebHandle = mock(PluginWebContributionHandle.class);
        final ScheduleCapabilityRegistry capabilityRegistry = new ScheduleCapabilityRegistry();
        final PluginScheduleContributionRegistrar scheduleRegistrar;
        final PluginLifecycleService service;

        ScheduleHarness(ScheduledWorkExecutor... preexistingExecutors) {
            plugin.scheduledSourceDescriptors = List.of(sourceDescriptor("ext-source", "ext-kind", "EXT_SOURCE"));
            registry.register(registered);
            registry.startFeature(registered);
            plugin.startCount = 0;
            if (preexistingExecutors.length > 0) {
                ScheduleCapabilityRegistryTestAccess.publish(
                        capabilityRegistry, ScheduleOwnerBundle.prepare(
                        new ScheduleCapabilityOwner("preexisting", "preexisting", 0L),
                        List.of(), List.of(), List.of(preexistingExecutors), List.of(), List.of()));
            }
            scheduleRegistrar = ScheduleCapabilityRegistryTestAccess.registrar(
                    capabilityRegistry, noOpMigrationService(), registry);
            PluginContextModule module = new PluginContextModule(
                    "ext-sched", ScheduleHarness.class.getClassLoader(), List.of(ScheduleContribConfig.class));
            when(runtime.inspectContextModules()).thenReturn(List.of(module));
            when(webRegistrar.currentHandle(same(registered))).thenReturn(Optional.of(bootWebHandle));
            PreparedWebContribution preparedWeb = mock(PreparedWebContribution.class);
            when(webRegistrar.prepare(same(registered))).thenReturn(preparedWeb);
            when(webRegistrar.commit(same(preparedWeb))).thenReturn(runtimeWebHandle);
            PluginStreamRegistry streamRegistry = new PluginStreamRegistry();
            QueueOperationRegistry queueRegistry = new QueueOperationRegistry(List.of());
            service = new PluginLifecycleService(parent, runtime, new PluginApplicationContextFactory(),
                    controllerRegistrar, webRegistrar, scheduleRegistrar,
                    runtimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry),
                    capabilityRegistrar(), registry, state);
        }

        @Override
        public void close() {
            parent.close();
        }
    }

    private static ScheduledSourceDescriptor sourceDescriptor(
            String sourceType, String workType, String... legacyAliases) {
        return new ScheduledSourceDescriptor(
                sourceType,
                Set.of(legacyAliases),
                sourceType + ".definition",
                1,
                new ScheduledSourcePresentation(
                        "test", "source.name", "source.description", "schedule", "neutral"),
                Set.of("schedule"),
                Set.of(workType),
                Set.of(),
                Set.of(),
                null);
    }

    private static ScheduledSourceExecutor sourceExecutor(String sourceType) {
        ScheduledSourceExecutor executor = mock(ScheduledSourceExecutor.class);
        when(executor.sourceType()).thenReturn(sourceType);
        return executor;
    }

    private static ScheduledWorkExecutor workExecutor(String workType) {
        ScheduledWorkExecutor executor = mock(ScheduledWorkExecutor.class);
        when(executor.workType()).thenReturn(workType);
        return executor;
    }

    /** 子 context 装配定义：核心服务消费 Bean + 匹配 descriptor 的现代来源 / 作品执行器。 */
    @Configuration
    static class ScheduleContribConfig {
        @Bean
        PluginBean pluginBean(CoreApiService coreService) {
            return new PluginBean(coreService);
        }

        @Bean
        ScheduledSourceExecutor extSourceExecutor() {
            return sourceExecutor("ext-source");
        }

        @Bean
        ScheduledWorkExecutor extWorkExecutor() {
            return workExecutor("ext-kind");
        }
    }
}
