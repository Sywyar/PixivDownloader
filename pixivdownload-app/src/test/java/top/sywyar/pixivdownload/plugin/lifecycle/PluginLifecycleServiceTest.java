package top.sywyar.pixivdownload.plugin.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.web.registration.PluginWebContributionRegistrar.PreparedWebContribution;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
    @DisplayName("隔离 worker 崩溃撤回服务足迹，恢复后仅重建同 generation")
    void isolatedWorkerCrashWithdrawsAndRecoveryRestoresServingFootprint() {
        MockHarness h = new MockHarness();
        Consumer<PluginRuntimeManager.WorkerEvent> listener = h.workerListener.get();

        listener.accept(new PluginRuntimeManager.WorkerEvent(
                PluginRuntimeManager.WorkerEventType.CRASHED,
                "ext-demo", 2L, "1.0.0", Path.of("target/ext-demo.jar"),
                1, 0, "stale worker", Path.of("target/ext-demo.stderr.log")));
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STARTED);

        listener.accept(new PluginRuntimeManager.WorkerEvent(
                PluginRuntimeManager.WorkerEventType.CRASHED,
                "ext-demo", 1L, "1.0.0", Path.of("target/ext-demo.jar"),
                1, 0, "worker exited", Path.of("target/ext-demo.stderr.log")));

        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
        assertThat(h.plugin.stopCount).isEqualTo(1);
        verify(h.registry).recordLifecycleFailure(same(h.registered), any(PluginLifecycleException.class));

        listener.accept(new PluginRuntimeManager.WorkerEvent(
                PluginRuntimeManager.WorkerEventType.RECOVERED,
                "ext-demo", 1L, "1.0.0", Path.of("target/ext-demo.jar"),
                1, 1, "isolated plugin worker restarted", null));

        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STARTED);
        assertThat(h.plugin.startCount).isEqualTo(1);
        verify(h.webRegistrar).commit(any(PreparedWebContribution.class));

        h.service.stopAll();
        verify(h.runtime).removeWorkerListener(same(listener));
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
