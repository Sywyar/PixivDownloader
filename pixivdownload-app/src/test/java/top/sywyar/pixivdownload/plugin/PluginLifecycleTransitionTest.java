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
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationService;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.maintenance.MaintenanceTaskRegistry;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTask;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskDrain;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRegistrar;
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
import top.sywyar.pixivdownload.plugin.lifecycle.capability.MaintenanceTaskCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPublication;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestGenerationDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLease;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLeaseRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestOwner;
import top.sywyar.pixivdownload.core.schedule.capability.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.plugin.api.stream.PluginStream;
import top.sywyar.pixivdownload.plugin.runtime.stream.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry;
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
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.LoadedPluginPackage;
import top.sywyar.pixivdownload.plugin.web.mapping.PluginAwareRequestMappingHandlerMapping;
import top.sywyar.pixivdownload.plugin.web.registration.PluginControllerRegistrar;
import top.sywyar.pixivdownload.plugin.web.registration.PluginWebContributionRegistrar;
import top.sywyar.pixivdownload.plugin.web.registration.PluginWebContributionRegistrar.PreparedWebContribution;
import top.sywyar.pixivdownload.plugin.web.registration.PluginWebContributionHandle;
import top.sywyar.pixivdownload.plugin.web.resource.PluginOwnedWebAssetValidator;
import top.sywyar.pixivdownload.scripts.ScriptRegistry;
import top.sywyar.pixivdownload.scripts.UserscriptRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
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

@DisplayName("插件生命周期：状态迁移与服务足迹")
class PluginLifecycleTransitionTest extends PluginLifecycleServiceTestSupport {

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
                new PluginApplicationContextFactory(
                        new PluginStreamRegistry(), new PluginRuntimeTaskRegistry()),
                mock(PluginControllerRegistrar.class),
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
}
