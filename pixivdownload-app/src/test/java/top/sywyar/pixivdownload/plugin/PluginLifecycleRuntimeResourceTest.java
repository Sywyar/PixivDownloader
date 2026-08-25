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

@DisplayName("插件生命周期：运行期任务与请求资源")
class PluginLifecycleRuntimeResourceTest extends PluginLifecycleServiceTestSupport {

    @Test
    @DisplayName("后台任务普通取消失败保持 QUIESCED 与子上下文，重试同代成功后才拆足迹")
    void runtimeTaskCancellationFailureKeepsContextForSameGenerationRetry() {
        try (ContextHarness h = new ContextHarness()) {
            h.service.startAll();
            ConfigurableApplicationContext child = h.service.contextFor("ext-demo").orElseThrow();
            Future<?> cancellation = mock(Future.class);
            doThrow(new IllegalStateException("task-cancel-failed"))
                    .doReturn(true)
                    .when(cancellation).cancel(false);
            PluginRuntimeTask task = child.getBean(PluginRuntimeTaskRegistrar.class)
                    .registerPeriodic(() -> {
                    });
            task.bindCancellation(cancellation);

            assertThatThrownBy(() -> h.service.stop("ext-demo"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("task-cancel-failed");

            PluginRuntimeTaskDrain retained = h.taskRegistry.prepareQuiesce("ext-demo");
            assertThat(retained.ownerPluginId()).isEqualTo("ext-demo");
            assertThat(retained.generation()).isPositive();
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);
            assertThat(child.isActive()).isTrue();
            assertThat(h.service.contextFor("ext-demo")).contains(child);
            verify(h.controllerRegistrar, never()).unregisterControllers("ext-demo");
            verify(h.webRegistrar, never()).unregister(same(h.bootWebHandle));

            h.service.stop("ext-demo");

            verify(cancellation, times(2)).cancel(false);
            assertThat(retained.isDrained()).isTrue();
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
            assertThat(child.isActive()).isFalse();
        }
    }

    @Test
    @DisplayName("后台任务致命取消失败保留原对象与子上下文，重试仍使用原 generation")
    void fatalRuntimeTaskCancellationKeepsIdentityAndRetryableContext() {
        try (ContextHarness h = new ContextHarness()) {
            h.service.startAll();
            ConfigurableApplicationContext child = h.service.contextFor("ext-demo").orElseThrow();
            OutOfMemoryError fatal = new OutOfMemoryError("task-cancel-fatal");
            Future<?> cancellation = mock(Future.class);
            doThrow(fatal).doReturn(true).when(cancellation).cancel(false);
            PluginRuntimeTask task = child.getBean(PluginRuntimeTaskRegistrar.class)
                    .registerPeriodic(() -> {
                    });
            task.bindCancellation(cancellation);

            assertThatThrownBy(() -> h.service.stop("ext-demo")).isSameAs(fatal);

            PluginRuntimeTaskDrain retained = h.taskRegistry.prepareQuiesce("ext-demo");
            long generation = retained.generation();
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);
            assertThat(child.isActive()).isTrue();
            verify(h.controllerRegistrar, never()).unregisterControllers("ext-demo");
            verify(h.webRegistrar, never()).unregister(same(h.bootWebHandle));

            h.service.stop("ext-demo");

            verify(cancellation, times(2)).cancel(false);
            assertThat(retained.generation()).isEqualTo(generation);
            assertThat(retained.isDrained()).isTrue();
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
            assertThat(child.isActive()).isFalse();
        }
    }

    @Test
    @DisplayName("运行中的中性后台任务退出前 stop 保持 QUIESCED 与子上下文")
    void stopWaitsForRunningRuntimeTaskBeforeClosingChildContext() throws Exception {
        try (ContextHarness h = new ContextHarness()) {
            h.service.startAll();
            ConfigurableApplicationContext child = h.service.contextFor("ext-demo").orElseThrow();
            CountDownLatch taskEntered = new CountDownLatch(1);
            CountDownLatch cancellationObserved = new CountDownLatch(1);
            CountDownLatch releaseTask = new CountDownLatch(1);
            PluginRuntimeTask task = child.getBean(PluginRuntimeTaskRegistrar.class)
                    .registerOneShot(() -> {
                        taskEntered.countDown();
                        try {
                            if (!releaseTask.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("timed out waiting to release runtime task");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("runtime task interrupted", failure);
                        }
                    });
            FutureTask<Void> hostFuture = new FutureTask<>(task, null) {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    cancellationObserved.countDown();
                    return super.cancel(mayInterruptIfRunning);
                }
            };
            task.bindCancellation(hostFuture);
            Thread worker = new Thread(hostFuture, "plugin-runtime-task");
            worker.start();
            assertThat(taskEntered.await(5, TimeUnit.SECONDS)).isTrue();

            AtomicReference<Throwable> stopFailure = new AtomicReference<>();
            Thread stop = new Thread(() -> {
                try {
                    h.service.stop("ext-demo");
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            }, "plugin-stop-await-runtime-task");
            stop.start();
            assertThat(cancellationObserved.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(stop.isAlive()).isTrue();
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);
            assertThat(child.isActive()).isTrue();
            verify(h.controllerRegistrar, never()).unregisterControllers("ext-demo");
            verify(h.webRegistrar, never()).unregister(same(h.bootWebHandle));

            releaseTask.countDown();
            worker.join(5000);
            stop.join(5000);

            assertThat(worker.isAlive()).isFalse();
            assertThat(stop.isAlive()).isFalse();
            assertThat(stopFailure.get()).isNull();
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
            assertThat(child.isActive()).isFalse();
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
                        h.streamRegistry.registrarForPlugin("ext-demo")
                                .register("late-stream", lateStream);
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
}
