package top.sywyar.pixivdownload.plugin.lifecycle;

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
import top.sywyar.pixivdownload.plugin.registry.web.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.download.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.WebUiSlotRegistry;
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

@DisplayName("插件生命周期：启动发布与失败补偿")
class PluginLifecycleBringUpTest extends PluginLifecycleServiceTestSupport {

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
            assertThatThrownBy(() -> h.streamRegistry.registrarForPlugin("ext-demo")
                    .register("late-stream", () -> {
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
}
