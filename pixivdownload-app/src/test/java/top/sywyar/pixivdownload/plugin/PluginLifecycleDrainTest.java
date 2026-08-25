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

@DisplayName("插件生命周期：队列与清退顺序")
class PluginLifecycleDrainTest extends PluginLifecycleServiceTestSupport {

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
                    parent, runtime, new PluginApplicationContextFactory(
                            new PluginStreamRegistry(), new PluginRuntimeTaskRegistry()),
                    controllerRegistrar, webRegistrar,
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
            verify(quiescer, times(1)).prepareRuntimeTaskDrain(eq("ext-demo"), any(), any());
            verify(quiescer, times(1)).prepareQueueDrains(eq("ext-demo"), any(), any());
            verify(quiescer, times(1)).quiesceAfterScheduleWithdrawal(eq("ext-demo"), any(), any());
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
        PluginRuntimeTaskRegistry taskRegistry = new PluginRuntimeTaskRegistry();
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
                scheduleRegistrar, streamRegistry, new QueueOperationRegistry(List.of()), taskRegistry);
        PluginLifecycleService service = new PluginLifecycleService(
                mock(ApplicationContext.class), runtime,
                new PluginApplicationContextFactory(streamRegistry, taskRegistry),
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
                .when(quiescer).quiesceAfterScheduleWithdrawal(eq("ext-retry"), any(), any());
        PluginControllerRegistrar controllerRegistrar = mock(PluginControllerRegistrar.class);
        PluginLifecycleService service = new PluginLifecycleService(
                mock(ApplicationContext.class), runtime,
                new PluginApplicationContextFactory(
                        new PluginStreamRegistry(), new PluginRuntimeTaskRegistry()),
                controllerRegistrar, webRegistrar, mock(PluginScheduleContributionRegistrar.class),
                quiescer, mock(PluginCapabilityContributionRegistrar.class), registry,
                new PluginLifecycleState());
        service.startAll();

        service.stopAll();

        verify(quiescer, times(2)).quiesceAfterScheduleWithdrawal(eq("ext-retry"), any(), any());
        verify(controllerRegistrar).unregisterControllers("ext-retry");
        verify(webRegistrar).unregister(same(webHandle));
        assertThat(service.phase("ext-retry")).contains(PluginRuntimePhase.STOPPED);
    }
}
