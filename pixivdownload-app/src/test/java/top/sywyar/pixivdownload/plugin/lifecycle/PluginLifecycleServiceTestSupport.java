package top.sywyar.pixivdownload.plugin.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
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
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.TestDownloadTypeDescriptors;
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
abstract class PluginLifecycleServiceTestSupport {

    // ============================ 真实子 context 组 ============================

    // ============================ mock 组（按 pluginId 动词 + 隔离）============================

    /** 一个纯贡献外置插件（无子 context）的 mock 装置：注册器全 mock，便于验证调用与异常隔离。 */
    protected static final class MockHarness {
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
        final PluginRuntimeTaskRegistry taskRegistry = new PluginRuntimeTaskRegistry();
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
                    new PluginApplicationContextFactory(streamRegistry, taskRegistry),
                    controllerRegistrar, webRegistrar, scheduleRegistrar,
                    runtimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry, taskRegistry),
                    capabilityRegistrar, registry, state);
            service.startAll(); // 纯贡献插件登记为 STARTED
            // 注册一条该插件拥有的 SSE 推流（验证 quiesce / 卸载时被关闭）。
            streamRegistry.registrarForPlugin("ext-demo").register("conn-1", stream);
        }
    }

    // ============================ 运行期任务清退组（quiesce / 卸载时 drain 在途队列 + 关闭 SSE）============================

    // ============================ 停派发先于清退在途组（direct stop / unload / reload from STARTED）============================

    /**
     * 全 mock 协作者（含 mock 的 {@link PluginStreamRegistry} / {@link QueueOperationRegistry}）的装置：用 {@link InOrder}
     * 验证「精确撤回 schedule publication 先于清退在途（关 SSE → drain 队列）」——即 direct stop / unload / reload
     * 从 STARTED 进入 {@code doStop} 时也先 shield 再 drain，drain 窗口内调度器解析不到其来源 / 执行器、不再派发新一轮 run。
     */
    protected static final class OrderHarness {
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
        final PluginRuntimeTaskRegistry taskRegistry = mock(PluginRuntimeTaskRegistry.class);
        final PluginRuntimeTaskDrain taskDrain = mock(PluginRuntimeTaskDrain.class);
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
            when(taskRegistry.prepareQuiesce("ext-demo")).thenReturn(taskDrain);
            when(taskDrain.ownerPluginId()).thenReturn("ext-demo");
            when(taskDrain.generation()).thenReturn(1L);
            when(taskDrain.awaitDrained(anyLong())).thenReturn(true);
            when(taskDrain.isDrained()).thenReturn(true);
            when(queueRegistry.operationsForOwner("ext-demo"))
                    .thenReturn(List.of(new OwnedQueueOperations("ext-illust", queueOperations)));
            when(queueOperations.prepareQuiesce("ext-illust")).thenReturn(queueDrain);
            when(queueDrain.queueType()).thenReturn("ext-illust");
            when(queueDrain.generation()).thenReturn(1L);
            when(queueDrain.awaitDrained(anyLong())).thenReturn(true);
            when(queueDrain.isDrained()).thenReturn(true);
            service = new PluginLifecycleService(mock(ApplicationContext.class), runtime,
                    new PluginApplicationContextFactory(streamRegistry, taskRegistry),
                    controllerRegistrar, webRegistrar, scheduleRegistrar,
                    runtimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry, taskRegistry),
                    capabilityRegistrar, registry, state);
            service.startAll(); // 纯贡献插件登记为 STARTED
            clearInvocations(webRegistrar, requestDrain, scheduleRegistrar, streamRegistry, queueRegistry,
                    queueOperations, queueDrain, taskRegistry, taskDrain, capabilityRegistrar);
        }

        /** 断言 request → publication 撤回 → 保存 queue drain → 关 SSE / 发取消 → 同截止时间等待 → 注销。 */
        void verifyShieldThenDrain() {
            InOrder ord = inOrder(webRegistrar, requestDrain, scheduleRegistrar, drain,
                    taskRegistry, taskDrain, streamRegistry, queueRegistry,
                    queueOperations, queueDrain, capabilityRegistrar);
            ord.verify(webRegistrar).withdrawRequests(same(bootWebHandle)); // ① 先拒绝新 HTTP 请求
            ord.verify(scheduleRegistrar).withdraw(any(), eq(publication)); // ② 再拒绝新 schedule lease 并取消旧代
            ord.verify(taskRegistry).prepareQuiesce("ext-demo"); // ③ 保存中性后台任务 generation drain
            ord.verify(queueRegistry).operationsForOwner("ext-demo"); // ④ 再捕获并保存队列 generation drain
            ord.verify(queueOperations).prepareQuiesce("ext-illust");
            ord.verify(taskRegistry).cancelQuiescedTasks("ext-demo", taskDrain); // ⑤ drain 已保存后才发任务取消
            ord.verify(streamRegistry).closeForPlugin("ext-demo"); // ⑥ 再关闭 SSE 推流
            ord.verify(queueRegistry).operationsForOwner("ext-demo");
            ord.verify(queueOperations).prepareQuiesce("ext-illust");
            ord.verify(queueOperations).cancelQuiescedTasks(); // ⑦ queue drain 已保存后才发 callback
            ArgumentCaptor<Long> requestDeadline = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Long> scheduleDeadline = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Long> taskDeadline = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Long> queueDeadline = ArgumentCaptor.forClass(Long.class);
            ord.verify(requestDrain).awaitDrained(requestDeadline.capture());
            ord.verify(drain).awaitDrained(scheduleDeadline.capture());
            ord.verify(taskDrain).awaitDrained(taskDeadline.capture());
            ord.verify(queueDrain).awaitDrained(queueDeadline.capture());
            assertThat(scheduleDeadline.getValue()).isEqualTo(requestDeadline.getValue());
            assertThat(taskDeadline.getValue()).isEqualTo(requestDeadline.getValue());
            assertThat(queueDeadline.getValue()).isEqualTo(requestDeadline.getValue());
            ord.verify(streamRegistry).closeForPlugin("ext-demo"); // ⑧ request/task drain 后复核迟到 stream
            ord.verify(streamRegistry).activeStreamCount("ext-demo");
            ord.verify(webRegistrar).unregister(same(bootWebHandle)); // ⑨ drain 归零后 registrar 才 retire serving
        }
    }

    // ============================ 插件自身 start()/stop() 生命周期组（真实子 context + mock 注册器）============================

    /**
     * 真实父 context + 真实子 context 工厂 + mock 注册器 + 记录型插件的装置：验证「运行期 start/reload 调插件
     * start()、启动期不重复调、start() 失败回滚足迹」。{@code ext-demo} 声明了配置类（建子 context）且有核心注册条目。
     */
    protected static final class FirstCloseTrackingPluginStreamRegistry extends PluginStreamRegistry {
        protected final CountDownLatch firstCloseReturned = new CountDownLatch(1);
        protected final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int closeForPlugin(String pluginId) {
            int closed = super.closeForPlugin(pluginId);
            if (closeCalls.incrementAndGet() == 1) {
                firstCloseReturned.countDown();
            }
            return closed;
        }

        protected void awaitFirstCloseReturned() throws InterruptedException {
            assertThat(firstCloseReturned.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    protected static final class ContextHarness implements AutoCloseable {
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
        final PluginRuntimeTaskRegistry taskRegistry = new PluginRuntimeTaskRegistry();
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
            service = new PluginLifecycleService(parent, runtime,
                    new PluginApplicationContextFactory(streamRegistry, taskRegistry),
                    controllerRegistrar, webRegistrar, scheduleRegistrar,
                    runtimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry, taskRegistry),
                    capabilityRegistrar, registry, state);
        }

        @Override
        public void close() {
            parent.close();
        }
    }

    /** 真实可变 web/download registry + 真实子 context，用于验证部分清理的可重试生命周期。 */
    protected static final class StatefulWebLifecycleHarness implements AutoCloseable {
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
            PluginRuntimeTaskRegistry taskRegistry = new PluginRuntimeTaskRegistry();
            QueueOperationRegistry queueRegistry = new QueueOperationRegistry(List.of());
            service = new PluginLifecycleService(
                    parent, runtime, new PluginApplicationContextFactory(streamRegistry, taskRegistry),
                    controllerRegistrar,
                    webRegistrar, scheduleRegistrar,
                    runtimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry, taskRegistry),
                    capabilityRegistrar, registry, state);
        }

        @Override
        public void close() {
            parent.close();
        }
    }

    protected static final class FailingLifecycleRouteAccessRegistry extends RouteAccessRegistry {
        protected boolean failBeforeUnregister;

        protected FailingLifecycleRouteAccessRegistry(PluginRegistry registry) {
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

    protected static final class FailingLifecycleWebI18nBundleRegistry extends WebI18nBundleRegistry {
        protected boolean failBeforeRegister;

        protected FailingLifecycleWebI18nBundleRegistry(PluginRegistry registry) {
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

    // ============================ schedule 贡献热插拔组（真实子 context + 真实调度注册中心）============================

    // ============================ 夹具 ============================

    protected static PluginLifecycleService realService(ApplicationContext parent, List<PluginContextModule> modules) {
        return realService(parent, modules, pluginRegistryForModules(modules), capabilityRegistrar());
    }

    protected static PluginRegistry pluginRegistryForModules(List<PluginContextModule> modules) {
        List<DiscoveredFeaturePlugin> discovered = modules.stream()
                .map(module -> new DiscoveredFeaturePlugin(
                        module.sourcePluginId(), module.sourcePluginId(),
                        new RecordingPlugin(module.sourcePluginId()), module.classLoader()))
                .toList();
        return new PluginRegistry(
                List.of(), new PluginToggleProperties(), new PluginDiscoveryResult(discovered, List.of()));
    }

    protected static PluginLifecycleService realService(
            ApplicationContext parent,
            List<PluginContextModule> modules,
            PluginRegistry pluginRegistry,
            PluginCapabilityContributionRegistrar capabilityRegistrar) {
        PluginScheduleContributionRegistrar scheduleRegistrar = emptyScheduleRegistrar(pluginRegistry);
        PluginStreamRegistry streamRegistry = new PluginStreamRegistry();
        PluginRuntimeTaskRegistry taskRegistry = new PluginRuntimeTaskRegistry();
        QueueOperationRegistry queueRegistry = new QueueOperationRegistry(List.of());
        return new PluginLifecycleService(parent, runtimeReturning(modules),
                new PluginApplicationContextFactory(streamRegistry, taskRegistry),
                emptyControllerRegistrar(pluginRegistry), emptyWebRegistrar(pluginRegistry), scheduleRegistrar,
                runtimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry, taskRegistry),
                capabilityRegistrar,
                pluginRegistry, new PluginLifecycleState());
    }

    protected static PluginRuntimeTaskQuiescer runtimeTaskQuiescer(
            PluginScheduleContributionRegistrar scheduleRegistrar,
            PluginStreamRegistry streamRegistry,
            QueueOperationRegistry queueRegistry,
            PluginRuntimeTaskRegistry taskRegistry) {
        return new PluginRuntimeTaskQuiescer(
                scheduleRegistrar, streamRegistry, queueRegistry, taskRegistry);
    }

    /** 模拟 phase 0 已成功启动精确身份，并让 mock 保持与 PluginRegistry 相同的成功后状态转换语义。 */
    protected static void delegateFeatureCallbacks(
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

    protected static PluginScheduleContributionRegistrar emptyScheduleRegistrar(PluginRegistry pluginRegistry) {
        return ScheduleCapabilityRegistryTestAccess.registrar(
                new ScheduleCapabilityRegistry(), noOpMigrationService(),
                pluginRegistry);
    }

    protected static LegacyScheduledTaskMigrationService noOpMigrationService() {
        return (reservation, adapter) ->
                new LegacyScheduledTaskMigrationService.OwnerMigrationReport("unused", 0, 0, 0, 0);
    }

    protected static PluginCapabilityContributionRegistrar capabilityRegistrar() {
        return new PluginCapabilityContributionRegistrar(List.of());
    }

    protected static PluginRuntimeManager runtimeReturning(List<PluginContextModule> modules) {
        return new PluginRuntimeManager(Path.of("target/no-such-plugins-dir")) {
            @Override
            public List<PluginContextModule> inspectContextModules() {
                return modules;
            }
        };
    }

    protected static PluginControllerRegistrar emptyControllerRegistrar(PluginRegistry pluginRegistry) {
        return new PluginControllerRegistrar(new PluginAwareRequestMappingHandlerMapping(),
                new RouteAccessRegistry(pluginRegistry));
    }

    protected static PluginWebContributionRegistrar emptyWebRegistrar(PluginRegistry pluginRegistry) {
        RouteAccessRegistry routes = new RouteAccessRegistry(pluginRegistry);
        StaticResourceRegistry statics = new StaticResourceRegistry(pluginRegistry);
        WebUiSlotRegistry slots = new WebUiSlotRegistry(pluginRegistry);
        UserscriptRegistry userscripts = new UserscriptRegistry(pluginRegistry);
        ScriptRegistry scripts = new ScriptRegistry(TestI18nBeans.appMessages(), userscripts);
        DownloadExtensionRegistry downloads = new DownloadExtensionRegistry(
                pluginRegistry, statics, new PluginOwnedWebAssetValidator(statics), slots);
        return new PluginWebContributionRegistrar(
                routes, statics, new WebI18nBundleRegistry(pluginRegistry), new NavigationRegistry(pluginRegistry),
                slots, userscripts, scripts, pluginRegistry, downloads);
    }

    protected static MaintenanceTask maintenanceTask(String name) {
        return new MaintenanceTask() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void execute(MaintenanceContext context) {
            }
        };
    }

    /** 记录 start() / stop() 调用次数的功能插件夹具（验证生命周期被调、幂等，{@code failStart} 可令 start() 抛异常）。 */
    protected static final class RecordingPlugin implements PixivFeaturePlugin {
        protected final String id;
        protected int startCount;
        protected int stopCount;
        protected boolean failStart;
        protected boolean failStartWithError;
        protected boolean failDownloadTypesWithError;
        protected String queueType; // 非空时声明对应作品类型（验证 quiesce / 卸载时排空其在途队列）
        protected List<ScheduledSourceDescriptor> scheduledSourceDescriptors = List.of();

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
                    "classpath:/test-download/", downloadPublicPrefix()));
        }

        protected String downloadPublicPrefix() {
            return "/" + id + "-download/";
        }

        protected String downloadModuleUrl() {
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

    protected static final class StatefulLifecyclePlugin implements PixivFeaturePlugin {
        protected int startCount;
        protected int stopCount;
        protected volatile CountDownLatch startEntered;
        protected volatile CountDownLatch releaseStart;

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
                    "classpath:/test-download/", "/stateful-owner/"));
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
    protected static final class RecordingQueueOperations implements QueueOperations {
        protected final String type;
        protected final top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker tracker;
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
    protected static final class RecordingStream implements PluginStream {
        int closedCount;

        @Override
        public void closeUnavailable() {
            closedCount++;
        }
    }

    interface CoreApiService {
        String describe();
    }

    @TestConfiguration
    static class ParentCoreConfig {
        @Bean
        CoreApiService coreApiService() {
            return () -> "core";
        }
    }

    static final class PluginBean {
        protected final CoreApiService coreService;

        PluginBean(CoreApiService coreService) {
            this.coreService = coreService;
        }

        CoreApiService coreService() {
            return coreService;
        }
    }

    @TestConfiguration
    static class PluginConfig {
        @Bean
        PluginBean pluginBean(CoreApiService coreService) {
            return new PluginBean(coreService);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class UnconditionalMaintenanceConfig {
        @Bean
        MaintenanceTask externalMaintenanceTask() {
            return maintenanceTask("external-maintenance");
        }
    }

    /** 依赖一个父 context 不提供的类型，refresh 时无法满足 → 子 context 建立失败（验证失败隔离）。 */
    @TestConfiguration
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
    protected static final class ScheduleHarness implements AutoCloseable {
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
            PluginRuntimeTaskRegistry taskRegistry = new PluginRuntimeTaskRegistry();
            QueueOperationRegistry queueRegistry = new QueueOperationRegistry(List.of());
            service = new PluginLifecycleService(parent, runtime,
                    new PluginApplicationContextFactory(streamRegistry, taskRegistry),
                    controllerRegistrar, webRegistrar, scheduleRegistrar,
                    runtimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry, taskRegistry),
                    capabilityRegistrar(), registry, state);
        }

        @Override
        public void close() {
            parent.close();
        }
    }

    protected static ScheduledSourceDescriptor sourceDescriptor(
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

    protected static ScheduledSourceExecutor sourceExecutor(String sourceType) {
        ScheduledSourceExecutor executor = mock(ScheduledSourceExecutor.class);
        when(executor.sourceType()).thenReturn(sourceType);
        return executor;
    }

    protected static ScheduledWorkExecutor workExecutor(String workType) {
        ScheduledWorkExecutor executor = mock(ScheduledWorkExecutor.class);
        when(executor.workType()).thenReturn(workType);
        return executor;
    }

    /** 子 context 装配定义：核心服务消费 Bean + 匹配 descriptor 的现代来源 / 作品执行器。 */
    @TestConfiguration
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
