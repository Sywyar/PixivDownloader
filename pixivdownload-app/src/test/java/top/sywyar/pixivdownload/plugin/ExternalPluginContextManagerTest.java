package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationService;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.scripts.ScriptRegistry;
import top.sywyar.pixivdownload.scripts.UserscriptRegistry;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleState;
import top.sywyar.pixivdownload.core.schedule.capability.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.quiesce.PluginRuntimeTaskQuiescer;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.web.PluginAwareRequestMappingHandlerMapping;
import top.sywyar.pixivdownload.plugin.web.PluginControllerRegistrar;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebAssetValidator;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionRegistrar;

/**
 * 外置插件子 context 生命周期的 {@code SmartLifecycle} 驱动测试：验证 {@link ExternalPluginContextManager} 把核心壳
 * 的启动 / 关闭时机桥接到 {@link PluginLifecycleService}，并转发可观测查询（子 context 持有情况）。具体的按插件
 * 建立 / 拆除 / 热启停 / quiesce 编排由 {@link PluginLifecycleServiceTest} 覆盖。
 */
@DisplayName("外置插件子 context 生命周期 SmartLifecycle 驱动")
class ExternalPluginContextManagerTest {

    @Test
    @DisplayName("SmartLifecycle 实际按 registry 先启动后停止、外置服务足迹后启动先停止")
    void lifecyclePhasesEnforceCallbackAndServingOrder() {
        List<String> events = new java.util.ArrayList<>();
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override public String id() { return "order-demo"; }
            @Override public String displayName() { return "plugin.name"; }
            @Override public String description() { return "plugin.summary"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public void start() { events.add("plugin-start"); }
            @Override public void stop() { events.add("plugin-stop"); }
        };
        PluginRegistry registry = new PluginRegistry(List.of(plugin));
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        doAnswer(ignored -> { events.add("serving-start"); return null; }).when(lifecycle).startAll();
        doAnswer(ignored -> { events.add("serving-stop"); return null; }).when(lifecycle).stopAll();
        ExternalPluginContextManager manager = new ExternalPluginContextManager(lifecycle);

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("pluginRegistry", PluginRegistry.class, () -> registry);
        context.registerBean("externalPluginContextManager", ExternalPluginContextManager.class, () -> manager);
        context.refresh();
        assertThat(events).containsExactly("plugin-start", "serving-start");

        context.close();
        assertThat(events).containsExactly(
                "plugin-start", "serving-start", "serving-stop", "plugin-stop");
    }

    @Test
    @DisplayName("start 驱动建立、stop 驱动拆除：转发 isRunning / count / pluginIds / contextFor")
    void delegatesStartStopAndObservabilityToLifecycleService() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            PluginContextModule module = new PluginContextModule(
                    "ext-demo", getClass().getClassLoader(), List.of(PluginConfig.class));
            PluginLifecycleService service = lifecycleService(parent, List.of(module));
            ExternalPluginContextManager manager = new ExternalPluginContextManager(service);

            assertThat(manager.isRunning()).isFalse();

            manager.start();

            assertThat(manager.isRunning()).isTrue();
            assertThat(manager.count()).isEqualTo(1);
            assertThat(manager.pluginIds()).containsExactly("ext-demo");
            ConfigurableApplicationContext child = manager.contextFor("ext-demo").orElseThrow();
            assertThat(child.isActive()).isTrue();

            manager.stop();

            assertThat(manager.isRunning()).isFalse();
            assertThat(manager.count()).isZero();
            assertThat(child.isActive()).isFalse();
        }
    }

    @Test
    @DisplayName("无外置插件：管理器透明无副作用")
    void noExternalPluginsIsTransparent() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            ExternalPluginContextManager manager =
                    new ExternalPluginContextManager(lifecycleService(parent, List.of()));

            manager.start();

            assertThat(manager.isRunning()).isTrue();
            assertThat(manager.count()).isZero();
            assertThat(manager.pluginIds()).isEmpty();

            manager.stop();
        }
    }

    // --- 夹具 ---

    private static PluginLifecycleService lifecycleService(ApplicationContext parent,
                                                           List<PluginContextModule> modules) {
        PluginRuntimeManager runtime = new PluginRuntimeManager(Path.of("target/no-such-plugins-dir")) {
            @Override
            public List<PluginContextModule> inspectContextModules() {
                return modules;
            }
        };
        List<DiscoveredFeaturePlugin> discovered = modules.stream()
                .map(module -> new DiscoveredFeaturePlugin(
                        module.sourcePluginId(), new FixturePlugin(module.sourcePluginId()), module.classLoader()))
                .toList();
        PluginRegistry pluginRegistry = new PluginRegistry(
                List.of(), new PluginToggleProperties(), new PluginDiscoveryResult(discovered, List.of()));
        RouteAccessRegistry routes = new RouteAccessRegistry(pluginRegistry);
        PluginControllerRegistrar controllerRegistrar = new PluginControllerRegistrar(
                new PluginAwareRequestMappingHandlerMapping(), routes);
        StaticResourceRegistry statics = new StaticResourceRegistry(pluginRegistry);
        WebUiSlotRegistry slots = new WebUiSlotRegistry(pluginRegistry);
        UserscriptRegistry userscripts = new UserscriptRegistry(pluginRegistry);
        ScriptRegistry scripts = new ScriptRegistry(TestI18nBeans.appMessages(), userscripts);
        DownloadExtensionRegistry downloads = new DownloadExtensionRegistry(
                pluginRegistry, statics, new PluginOwnedWebAssetValidator(statics), slots);
        PluginWebContributionRegistrar webRegistrar = new PluginWebContributionRegistrar(
                routes, statics, new WebI18nBundleRegistry(pluginRegistry), new NavigationRegistry(pluginRegistry),
                slots, userscripts, scripts, pluginRegistry, downloads);
        PluginScheduleContributionRegistrar scheduleRegistrar =
                ScheduleCapabilityRegistryTestAccess.registrar(
                        new ScheduleCapabilityRegistry(), (reservation, adapter) ->
                        new LegacyScheduledTaskMigrationService.OwnerMigrationReport(
                                "unused", 0, 0, 0, 0), pluginRegistry);
        PluginCapabilityContributionRegistrar capabilityRegistrar = new PluginCapabilityContributionRegistrar(List.of());
        PluginStreamRegistry streamRegistry = new PluginStreamRegistry();
        QueueOperationRegistry queueRegistry = new QueueOperationRegistry(List.of());
        PluginRuntimeTaskQuiescer taskQuiescer =
                new PluginRuntimeTaskQuiescer(scheduleRegistrar, streamRegistry, queueRegistry);
        return new PluginLifecycleService(parent, runtime, new PluginApplicationContextFactory(),
                controllerRegistrar, webRegistrar, scheduleRegistrar, taskQuiescer, capabilityRegistrar, pluginRegistry,
                new PluginLifecycleState());
    }

    private record FixturePlugin(String id) implements PixivFeaturePlugin {
        @Override
        public String displayName() {
            return "plugin.name";
        }

        @Override
        public String description() {
            return "plugin.summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
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
    }

    @Configuration
    static class PluginConfig {
        @Bean
        PluginBean pluginBean() {
            return new PluginBean();
        }
    }
}
