package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.ai.AiChatClientRegistry;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunnerRegistry;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.notification.NotificationSinkRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.scripts.ScriptRegistry;
import top.sywyar.pixivdownload.scripts.UserscriptRegistry;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleState;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.ScheduledSourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;
import top.sywyar.pixivdownload.plugin.web.PluginAwareRequestMappingHandlerMapping;
import top.sywyar.pixivdownload.plugin.web.PluginControllerRegistrar;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionRegistrar;
import top.sywyar.pixivdownload.push.PushChannelRegistry;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationEngineRegistry;

/**
 * 外置插件子 context 生命周期的 {@code SmartLifecycle} 驱动测试：验证 {@link ExternalPluginContextManager} 把核心壳
 * 的启动 / 关闭时机桥接到 {@link PluginLifecycleService}，并转发可观测查询（子 context 持有情况）。具体的按插件
 * 建立 / 拆除 / 热启停 / quiesce 编排由 {@link PluginLifecycleServiceTest} 覆盖。
 */
@DisplayName("外置插件子 context 生命周期 SmartLifecycle 驱动")
class ExternalPluginContextManagerTest {

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
        PluginControllerRegistrar controllerRegistrar = new PluginControllerRegistrar(
                new PluginAwareRequestMappingHandlerMapping(), new RouteAccessRegistry(new PluginRegistry(List.of())));
        PluginRegistry empty = new PluginRegistry(List.of());
        UserscriptRegistry userscripts = new UserscriptRegistry(empty);
        ScriptRegistry scripts = new ScriptRegistry(TestI18nBeans.appMessages(), userscripts);
        PluginWebContributionRegistrar webRegistrar = new PluginWebContributionRegistrar(
                new RouteAccessRegistry(empty), new StaticResourceRegistry(empty),
                new WebI18nBundleRegistry(empty), new NavigationRegistry(empty),
                new WebUiSlotRegistry(empty), userscripts, scripts);
        PluginScheduleContributionRegistrar scheduleRegistrar = new PluginScheduleContributionRegistrar(
                new ScheduledSourceRegistry(empty), new ScheduledWorkRunnerRegistry(List.of()));
        PluginCapabilityContributionRegistrar capabilityRegistrar = new PluginCapabilityContributionRegistrar(
                new NotificationSinkRegistry(List.of()), new PushChannelRegistry(List.of()),
                new AiChatClientRegistry(List.of()), new NarrationEngineRegistry(List.of()));
        return new PluginLifecycleService(parent, runtime, new PluginApplicationContextFactory(),
                controllerRegistrar, webRegistrar, scheduleRegistrar, capabilityRegistrar, empty,
                new PluginLifecycleState(), new QueueOperationRegistry(List.of()), new PluginStreamRegistry());
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
