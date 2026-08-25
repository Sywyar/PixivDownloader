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
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
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

@DisplayName("插件生命周期：计划能力发布与恢复")
class PluginLifecycleScheduleTest extends PluginLifecycleServiceTestSupport {

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
    @DisplayName("已安装但禁用的外置插件不建立子 context，也不发布未条件化维护任务")
    void disabledExternalPluginDoesNotPublishUnconditionalMaintenanceTask() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            String pluginId = "ext-disabled";
            RecordingPlugin plugin = new RecordingPlugin(pluginId);
            PluginToggleProperties toggles = new PluginToggleProperties();
            toggles.setEnabled(pluginId, false);
            PluginRegistry pluginRegistry = new PluginRegistry(
                    List.of(), toggles,
                    new PluginDiscoveryResult(List.of(new DiscoveredFeaturePlugin(
                            pluginId, pluginId, plugin, getClass().getClassLoader())), List.of()));
            PluginContextModule module = new PluginContextModule(
                    pluginId, getClass().getClassLoader(), List.of(UnconditionalMaintenanceConfig.class));
            MaintenanceTask coreTask = maintenanceTask("core-maintenance");
            MaintenanceTaskRegistry taskRegistry = new MaintenanceTaskRegistry(List.of(coreTask));
            ExternalCapabilityInvocationRegistry invocationRegistry = new ExternalCapabilityInvocationRegistry();
            MaintenanceTaskCapabilityAdapter maintenanceAdapter =
                    new MaintenanceTaskCapabilityAdapter(taskRegistry, invocationRegistry);
            PluginCapabilityContributionRegistrar capabilityRegistrar =
                    new PluginCapabilityContributionRegistrar(
                            List.of(), List.of(), List.of(maintenanceAdapter), invocationRegistry);
            PluginLifecycleService service = realService(
                    parent, List.of(module), pluginRegistry, capabilityRegistrar);

            assertThat(pluginRegistry.allRegisteredPlugins())
                    .extracting(PluginRegistry.RegisteredPlugin::id)
                    .containsExactly(pluginId);
            assertThat(pluginRegistry.registeredPlugins()).isEmpty();

            service.startAll();

            assertThat(service.managedPluginIds()).isEmpty();
            assertThat(service.contextFor(pluginId)).isEmpty();
            assertThat(service.phase(pluginId)).isEmpty();
            assertThat(taskRegistry.tasks()).extracting(MaintenanceTask::name)
                    .containsExactly("core-maintenance");
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
}
