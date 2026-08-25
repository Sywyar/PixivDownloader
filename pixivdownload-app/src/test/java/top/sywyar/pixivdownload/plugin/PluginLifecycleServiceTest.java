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

@DisplayName("插件生命周期：启动期上下文与真实子上下文")
class PluginLifecycleServiceTest extends PluginLifecycleServiceTestSupport {

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
            List<PluginContextModule> modules = List.of(broken, good);
            PluginRegistry registry = pluginRegistryForModules(modules);
            PluginLifecycleService service = realService(parent, modules, registry, capabilityRegistrar());

            service.startAll();

            assertThat(service.servingPluginIds()).containsExactly("ext-good");
            assertThat(service.phase("ext-good")).contains(PluginRuntimePhase.STARTED);
            assertThat(service.contextFor("ext-broken")).isEmpty();
            assertThat(service.phase("ext-broken")).contains(PluginRuntimePhase.STOPPED);
            assertThat(registry.lifecycleFailuresById()).containsKey("ext-broken");

            service.stopAll();
        }
    }

    @Test
    @DisplayName("phase 0 插件 start 失败：不建立子 context，保留失败诊断并落 STOPPED")
    void bootFeatureStartFailureSkipsServingFootprint() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            String pluginId = "ext-start-broken";
            RecordingPlugin plugin = new RecordingPlugin(pluginId);
            plugin.failStart = true;
            PluginContextModule module = new PluginContextModule(
                    pluginId, getClass().getClassLoader(), List.of(PluginConfig.class));
            PluginRegistry registry = new PluginRegistry(
                    List.of(), new PluginToggleProperties(), new PluginDiscoveryResult(List.of(
                    new DiscoveredFeaturePlugin(pluginId, pluginId, plugin, getClass().getClassLoader())), List.of()));
            PluginLifecycleService service = realService(
                    parent, List.of(module), registry, capabilityRegistrar());

            registry.start();
            service.startAll();

            assertThat(service.contextFor(pluginId)).isEmpty();
            assertThat(service.phase(pluginId)).contains(PluginRuntimePhase.STOPPED);
            assertThat(registry.lifecycleFailuresById()).containsKey(pluginId);
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
}
