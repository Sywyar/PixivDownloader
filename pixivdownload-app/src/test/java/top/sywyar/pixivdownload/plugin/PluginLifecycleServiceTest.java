package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.scripts.ScriptRegistry;
import top.sywyar.pixivdownload.scripts.UserscriptRegistry;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
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
@DisplayName("外置插件运行期热启停 / quiesce 生命周期服务")
class PluginLifecycleServiceTest {

    // ============================ 真实子 context 组 ============================

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
            PluginLifecycleService service = realService(parent, List.of(broken, good));

            service.startAll();

            assertThat(service.servingPluginIds()).containsExactly("ext-good");
            assertThat(service.phase("ext-good")).contains(PluginRuntimePhase.STARTED);
            assertThat(service.contextFor("ext-broken")).isEmpty();
            assertThat(service.phase("ext-broken")).contains(PluginRuntimePhase.STOPPED);

            service.stopAll();
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

    // ============================ mock 组（按 pluginId 动词 + 隔离）============================

    /** 一个纯贡献外置插件（无子 context）的 mock 装置：注册器全 mock，便于验证调用与异常隔离。 */
    private static final class MockHarness {
        final PluginControllerRegistrar controllerRegistrar = mock(PluginControllerRegistrar.class);
        final PluginWebContributionRegistrar webRegistrar = mock(PluginWebContributionRegistrar.class);
        final PluginRegistry registry = mock(PluginRegistry.class);
        final PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
        final PluginLifecycleState state = new PluginLifecycleState();
        final RecordingPlugin plugin = new RecordingPlugin("ext-demo");
        final PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, MockHarness.class.getClassLoader());
        final PluginLifecycleService service;

        MockHarness() {
            when(runtime.inspectContextModules()).thenReturn(List.of());
            when(registry.registeredPlugins()).thenReturn(List.of(registered));
            service = new PluginLifecycleService(mock(ApplicationContext.class), runtime,
                    new PluginApplicationContextFactory(), controllerRegistrar, webRegistrar, registry, state);
            service.startAll(); // 纯贡献插件登记为 STARTED
        }
    }

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
    @DisplayName("stop：按序注销 controller / web 贡献、调插件 stop()，阶段落 STOPPED；重复 stop 幂等")
    void stopTearsDownAndIsIdempotent() {
        MockHarness h = new MockHarness();

        h.service.stop("ext-demo");

        verify(h.controllerRegistrar).unregisterControllers("ext-demo");
        verify(h.webRegistrar).unregister(eq("ext-demo"), any());
        assertThat(h.plugin.stopCount).isEqualTo(1);
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);

        // 重复 stop 不破坏状态、不再次清退
        h.service.stop("ext-demo");
        verify(h.webRegistrar, times(1)).unregister(eq("ext-demo"), any());
        assertThat(h.plugin.stopCount).isEqualTo(1);
    }

    @Test
    @DisplayName("stop 中某一步异常：后续清退仍发生、阶段仍落 STOPPED")
    void stopStepFailureDoesNotBlockTeardown() {
        MockHarness h = new MockHarness();
        // 第一步（注销 controller）抛异常，验证后续步骤仍执行、状态仍收尾。
        doThrow(new RuntimeException("boom")).when(h.controllerRegistrar).unregisterControllers("ext-demo");

        h.service.stop("ext-demo");

        verify(h.webRegistrar).unregister(eq("ext-demo"), any()); // 仍清退 web 贡献
        assertThat(h.plugin.stopCount).isEqualTo(1);              // 仍调插件 stop()
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
    }

    @Test
    @DisplayName("unload：先停止再从核心注册中心移除，阶段落 UNLOADED")
    void unloadStopsThenUnregistersFromCore() {
        MockHarness h = new MockHarness();

        h.service.unload("ext-demo");

        verify(h.webRegistrar).unregister(eq("ext-demo"), any()); // 经 stop 拆服务足迹
        verify(h.registry).unregister("ext-demo");                // 从核心注册中心移除
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.UNLOADED);
        // 幂等
        h.service.unload("ext-demo");
        verify(h.registry, times(1)).unregister("ext-demo");
    }

    @Test
    @DisplayName("load：把已卸下插件重新接入核心注册中心，阶段落 LOADED；非 UNLOADED 时 load 抛诊断")
    void loadReregistersIntoCore() {
        MockHarness h = new MockHarness();
        h.service.unload("ext-demo"); // → UNLOADED

        h.service.load("ext-demo");

        verify(h.registry).register(h.plugin, PluginSource.EXTERNAL, h.registered.classLoader());
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.LOADED);

        // load 之后可再 start（重新接入 web 贡献 + 重新调插件 start()）
        h.service.start("ext-demo");
        verify(h.webRegistrar).register(h.registered);
        assertThat(h.plugin.startCount).isEqualTo(1);
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STARTED);
    }

    @Test
    @DisplayName("reload：stop 后再 start，阶段回到 STARTED、web 贡献重新接入、插件 stop()/start() 各一次")
    void reloadRecyclesServingFootprint() {
        MockHarness h = new MockHarness();

        h.service.reload("ext-demo");

        verify(h.webRegistrar).unregister(eq("ext-demo"), any());
        verify(h.webRegistrar).register(h.registered);
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

    // ============================ 插件自身 start()/stop() 生命周期组（真实子 context + mock 注册器）============================

    /**
     * 真实父 context + 真实子 context 工厂 + mock 注册器 + 记录型插件的装置：验证「运行期 start/reload 调插件
     * start()、启动期不重复调、start() 失败回滚足迹」。{@code ext-demo} 声明了配置类（建子 context）且有核心注册条目。
     */
    private static final class ContextHarness implements AutoCloseable {
        final AnnotationConfigApplicationContext parent =
                new AnnotationConfigApplicationContext(ParentCoreConfig.class);
        final PluginControllerRegistrar controllerRegistrar = mock(PluginControllerRegistrar.class);
        final PluginWebContributionRegistrar webRegistrar = mock(PluginWebContributionRegistrar.class);
        final PluginRegistry registry = mock(PluginRegistry.class);
        final PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
        final PluginLifecycleState state = new PluginLifecycleState();
        final RecordingPlugin plugin = new RecordingPlugin("ext-demo");
        final PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, ContextHarness.class.getClassLoader());
        final PluginLifecycleService service;

        ContextHarness() {
            PluginContextModule module = new PluginContextModule(
                    "ext-demo", ContextHarness.class.getClassLoader(), List.of(PluginConfig.class));
            when(runtime.inspectContextModules()).thenReturn(List.of(module));
            when(registry.registeredPlugins()).thenReturn(List.of(registered));
            service = new PluginLifecycleService(parent, runtime, new PluginApplicationContextFactory(),
                    controllerRegistrar, webRegistrar, registry, state);
        }

        @Override
        public void close() {
            parent.close();
        }
    }

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
            verify(h.webRegistrar).unregister(eq("ext-demo"), any());                      // web 贡献回滚
        }
    }

    // ============================ 夹具 ============================

    private static PluginLifecycleService realService(ApplicationContext parent, List<PluginContextModule> modules) {
        return new PluginLifecycleService(parent, runtimeReturning(modules), new PluginApplicationContextFactory(),
                emptyControllerRegistrar(), emptyWebRegistrar(), new PluginRegistry(List.of()),
                new PluginLifecycleState());
    }

    private static PluginRuntimeManager runtimeReturning(List<PluginContextModule> modules) {
        return new PluginRuntimeManager(Path.of("target/no-such-plugins-dir")) {
            @Override
            public List<PluginContextModule> inspectContextModules() {
                return modules;
            }
        };
    }

    private static PluginControllerRegistrar emptyControllerRegistrar() {
        return new PluginControllerRegistrar(new PluginAwareRequestMappingHandlerMapping(),
                new RouteAccessRegistry(new PluginRegistry(List.of())));
    }

    private static PluginWebContributionRegistrar emptyWebRegistrar() {
        PluginRegistry empty = new PluginRegistry(List.of());
        UserscriptRegistry userscripts = new UserscriptRegistry(empty);
        ScriptRegistry scripts = new ScriptRegistry(TestI18nBeans.appMessages(), userscripts);
        return new PluginWebContributionRegistrar(
                new RouteAccessRegistry(empty), new StaticResourceRegistry(empty),
                new WebI18nBundleRegistry(empty), new NavigationRegistry(empty), userscripts, scripts);
    }

    /** 记录 start() / stop() 调用次数的功能插件夹具（验证生命周期被调、幂等，{@code failStart} 可令 start() 抛异常）。 */
    private static final class RecordingPlugin implements PixivFeaturePlugin {
        private final String id;
        private int startCount;
        private int stopCount;
        private boolean failStart;

        RecordingPlugin(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
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
        }

        @Override
        public void stop() {
            stopCount++;
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
        private final CoreApiService coreService;

        PluginBean(CoreApiService coreService) {
            this.coreService = coreService;
        }

        CoreApiService coreService() {
            return coreService;
        }
    }

    @Configuration
    static class PluginConfig {
        @Bean
        PluginBean pluginBean(CoreApiService coreService) {
            return new PluginBean(coreService);
        }
    }

    /** 依赖一个父 context 不提供的类型，refresh 时无法满足 → 子 context 建立失败（验证失败隔离）。 */
    @Configuration
    static class BrokenPluginConfig {
        @Bean
        PluginBean brokenBean(MissingDependency missing) {
            return new PluginBean(null);
        }
    }

    interface MissingDependency {
    }
}
