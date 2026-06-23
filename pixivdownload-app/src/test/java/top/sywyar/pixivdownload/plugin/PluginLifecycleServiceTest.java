package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.download.queue.QueueOperations;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunnerRegistry;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkSettings;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.scripts.ScriptRegistry;
import top.sywyar.pixivdownload.scripts.UserscriptRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
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
        final PluginScheduleContributionRegistrar scheduleRegistrar = mock(PluginScheduleContributionRegistrar.class);
        final PluginRegistry registry = mock(PluginRegistry.class);
        final PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
        final PluginLifecycleState state = new PluginLifecycleState();
        final RecordingPlugin plugin = new RecordingPlugin("ext-demo");
        final PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, MockHarness.class.getClassLoader());
        final RecordingQueueOperations ops;          // 非空当且仅当装置声明了 queueType
        final QueueOperationRegistry queueRegistry;
        final PluginStreamRegistry streamRegistry = new PluginStreamRegistry();
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
                queueRegistry = new QueueOperationRegistry(List.of(ops));
            } else {
                ops = null;
                queueRegistry = new QueueOperationRegistry(List.of());
            }
            when(runtime.inspectContextModules()).thenReturn(List.of());
            when(registry.registeredPlugins()).thenReturn(List.of(registered));
            service = new PluginLifecycleService(mock(ApplicationContext.class), runtime,
                    new PluginApplicationContextFactory(), controllerRegistrar, webRegistrar, scheduleRegistrar,
                    registry, state, queueRegistry, streamRegistry);
            service.startAll(); // 纯贡献插件登记为 STARTED
            // 注册一条该插件拥有的 SSE 推流（验证 quiesce / 卸载时被关闭）。
            streamRegistry.register("ext-demo", "conn-1", stream);
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
    @DisplayName("quiesce：注销该插件 schedule 贡献（停新计划任务派发）；随后 stop 再注销为幂等 no-op、不重复出错")
    void quiesceUnregistersScheduleContributionThenStopIsIdempotent() {
        MockHarness h = new MockHarness();

        h.service.quiesce("ext-demo");
        verify(h.scheduleRegistrar, atLeastOnce()).unregister("ext-demo"); // quiesce 即停新计划任务派发（注销来源 / 执行器）
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);

        // stop after quiesce：shield + tearDownServing 再注销（幂等、不重复出错），stop 不抛、阶段落 STOPPED。
        // 不写死注销次数（避免脆弱）：只验证 stop 仍幂等地再注销一次、且收尾到 STOPPED。
        clearInvocations(h.scheduleRegistrar);
        h.service.stop("ext-demo");
        verify(h.scheduleRegistrar, atLeastOnce()).unregister("ext-demo");
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
    }

    @Test
    @DisplayName("stop：按序注销 controller / schedule 贡献 / web 贡献、调插件 stop()，阶段落 STOPPED；重复 stop 幂等")
    void stopTearsDownAndIsIdempotent() {
        MockHarness h = new MockHarness();

        h.service.stop("ext-demo");

        verify(h.controllerRegistrar).unregisterControllers("ext-demo");
        verify(h.scheduleRegistrar, atLeastOnce()).unregister("ext-demo"); // shield（drain 前停派发）+ teardown 幂等再注销
        verify(h.webRegistrar).unregister(eq("ext-demo"), any());
        assertThat(h.plugin.stopCount).isEqualTo(1);
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);

        // 重复 stop 不破坏状态、不再次清退（已 STOPPED → 早返回，schedule / web 注销与插件 stop() 都不再发生）
        clearInvocations(h.scheduleRegistrar, h.webRegistrar);
        h.service.stop("ext-demo");
        verify(h.scheduleRegistrar, never()).unregister("ext-demo");
        verify(h.webRegistrar, never()).unregister(eq("ext-demo"), any());
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

        verify(h.scheduleRegistrar, atLeastOnce()).unregister("ext-demo"); // 经 stop（shield + teardown）注销 schedule 贡献
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

    // ============================ 运行期任务清退组（quiesce / 卸载时 drain 在途队列 + 关闭 SSE）============================

    @Test
    @DisplayName("stop：经核心队列宿主注册中心排空该插件 queueType 的在途任务 + 关闭其 SSE 推流，阶段落 STOPPED")
    void stopDrainsQueueTasksAndClosesStreams() {
        MockHarness h = new MockHarness("ext-illust");

        h.service.stop("ext-demo");

        assertThat(h.ops.clearAllCount).isEqualTo(1);                       // 在途下载经 QueueOperations.clearAll 取消
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
    @DisplayName("无 queueType 的纯贡献插件：stop 不触达队列注册中心（drain 安全空操作），仍关闭其 SSE")
    void stopWithoutQueueTypeStillClosesStreams() {
        MockHarness h = new MockHarness(); // 无 queueType

        h.service.stop("ext-demo");

        assertThat(h.stream.closedCount).isEqualTo(1);
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
    }

    @Test
    @DisplayName("长任务在插件卸载时被取消：unload 触发 drain（clearAll），阻塞等待的下载线程被放行、干净退出")
    void longRunningTaskIsCancelledOnUnload() throws Exception {
        MockHarness h = new MockHarness("ext-illust");
        AtomicBoolean released = new AtomicBoolean(false);
        // 模拟一个在途下载长任务：阻塞等待，直到队列被排空（clearAll 放行 latch）才退出 —— 即「被取消」。
        Thread worker = new Thread(() -> {
            try {
                released.set(h.ops.drained.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();

        h.service.unload("ext-demo"); // → stop（drain → clearAll 放行 latch）→ 从核心注册中心移除

        worker.join(3000);
        assertThat(released).isTrue();                 // 长任务观察到取消、干净退出（而非永久阻塞）
        assertThat(h.ops.clearAllCount).isEqualTo(1);
        assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.UNLOADED);
    }

    // ============================ 停派发先于清退在途组（direct stop / unload / reload from STARTED）============================

    /**
     * 全 mock 协作者（含 mock 的 {@link PluginStreamRegistry} / {@link QueueOperationRegistry}）的装置：用 {@link InOrder}
     * 验证「停新计划任务派发（注销 schedule 贡献）先于清退在途（关 SSE → drain 队列）」——即 direct stop / unload / reload
     * 从 STARTED 进入 {@code doStop} 时也先 shield 再 drain，drain 窗口内调度器解析不到其来源 / 执行器、不再派发新一轮 run。
     */
    private static final class OrderHarness {
        final PluginControllerRegistrar controllerRegistrar = mock(PluginControllerRegistrar.class);
        final PluginWebContributionRegistrar webRegistrar = mock(PluginWebContributionRegistrar.class);
        final PluginScheduleContributionRegistrar scheduleRegistrar = mock(PluginScheduleContributionRegistrar.class);
        final PluginRegistry registry = mock(PluginRegistry.class);
        final PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
        final PluginLifecycleState state = new PluginLifecycleState();
        final RecordingPlugin plugin = new RecordingPlugin("ext-demo");
        final PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, OrderHarness.class.getClassLoader());
        final PluginStreamRegistry streamRegistry = mock(PluginStreamRegistry.class);
        final QueueOperationRegistry queueRegistry = mock(QueueOperationRegistry.class);
        final PluginLifecycleService service;

        OrderHarness() {
            plugin.queueType = "ext-illust"; // 声明作品类型 → drain 会经 queueRegistry.resolve 解析其操作适配器
            when(runtime.inspectContextModules()).thenReturn(List.of());
            when(registry.registeredPlugins()).thenReturn(List.of(registered));
            service = new PluginLifecycleService(mock(ApplicationContext.class), runtime,
                    new PluginApplicationContextFactory(), controllerRegistrar, webRegistrar, scheduleRegistrar,
                    registry, state, queueRegistry, streamRegistry);
            service.startAll(); // 纯贡献插件登记为 STARTED
        }

        /** 断言 schedule 注销（停派发）→ 关 SSE → drain 队列 的相对调用顺序（schedule 注销可发生多次，只校验首次在前）。 */
        void verifyShieldThenDrain() {
            InOrder ord = inOrder(scheduleRegistrar, streamRegistry, queueRegistry);
            ord.verify(scheduleRegistrar).unregister("ext-demo");   // ① 先停新计划任务派发（注销来源 / 执行器）
            ord.verify(streamRegistry).closeForPlugin("ext-demo");  // ② 再关闭 SSE 推流
            ord.verify(queueRegistry).resolve("ext-illust");        // ③ 再 drain 在途下载队列
        }
    }

    @Test
    @DisplayName("direct stop（from STARTED）：schedule 注销（停派发）发生在关 SSE / drain 队列之前")
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
                    controllerRegistrar, webRegistrar, emptyScheduleRegistrar(), registry, state,
                    new QueueOperationRegistry(List.of()), new PluginStreamRegistry());
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

    // ============================ schedule 贡献热插拔组（真实子 context + 真实调度注册中心）============================

    @Test
    @DisplayName("startAll 注册外置插件 schedule 来源 + 执行器：规范 type / legacy 名 / 作品类型均可解析")
    void startAllRegistersScheduleSourceAndRunner() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();

            assertThat(h.sourceRegistry.resolve("ext-source")).isPresent();
            assertThat(h.sourceRegistry.resolve("EXT_SOURCE")).isPresent(); // legacy 名解析到同一来源
            assertThat(h.runnerRegistry.resolve("ext-kind")).isPresent();   // 执行器从子 context 发现
            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.STARTED);
        }
    }

    @Test
    @DisplayName("stop 注销 schedule 来源 + 执行器：解析均落空（残留任务即进 SOURCE_UNAVAILABLE 干净挂起、数据不删）")
    void stopUnregistersScheduleContributions() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();

            h.service.stop("ext-sched");

            assertThat(h.sourceRegistry.resolve("ext-source")).isEmpty();
            assertThat(h.runnerRegistry.resolve("ext-kind")).isEmpty();
            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.STOPPED);
        }
    }

    @Test
    @DisplayName("unload 注销 schedule 来源 + 执行器并从核心注册中心移除")
    void unloadUnregistersScheduleContributions() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();

            h.service.unload("ext-sched");

            assertThat(h.sourceRegistry.resolve("ext-source")).isEmpty();
            assertThat(h.runnerRegistry.resolve("ext-kind")).isEmpty();
            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.UNLOADED);
        }
    }

    @Test
    @DisplayName("reload 后 schedule 来源 + 执行器恢复：规范 type / 作品类型再次可解析（来源恢复路径）")
    void reloadRestoresScheduleContributions() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();

            h.service.reload("ext-sched");

            assertThat(h.sourceRegistry.resolve("ext-source")).isPresent();
            assertThat(h.runnerRegistry.resolve("ext-kind")).isPresent();
            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.STARTED);
        }
    }

    @Test
    @DisplayName("stop → start 往返：执行器解析 缺失→恢复（镜像 SOURCE_UNAVAILABLE 挂起后来源恢复可再被 findDue）")
    void stopThenStartRecoversRunnerResolution() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();
            assertThat(h.runnerRegistry.resolve("ext-kind")).isPresent();

            h.service.stop("ext-sched");
            assertThat(h.runnerRegistry.resolve("ext-kind")).isEmpty(); // 缺执行器 → 任务挂起、数据不删

            h.service.start("ext-sched");
            assertThat(h.runnerRegistry.resolve("ext-kind")).isPresent(); // 来源 / 执行器恢复、可再被调度
            assertThat(h.sourceRegistry.resolve("ext-source")).isPresent();
        }
    }

    @Test
    @DisplayName("schedule 注册失败（执行器 kind 冲突）：回滚 web/controller/子 context，落 STOPPED、本次来源不泄漏、既有执行器不污染")
    void scheduleRegisterFailureRollsBackFootprint() {
        // 预置一个 kind 与插件执行器冲突的执行器 → 插件执行器注册 fail-fast
        try (ScheduleHarness h = new ScheduleHarness(workRunner("ext-kind"))) {
            h.service.startAll();

            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.STOPPED); // 不进入 STARTED
            assertThat(h.service.contextFor("ext-sched")).isEmpty();                       // 子 context 已回收
            assertThat(h.sourceRegistry.resolve("ext-source")).isEmpty();                  // 本次来源被回滚、不泄漏
            assertThat(h.runnerRegistry.resolve("ext-kind")).isPresent();                  // 预置执行器未被污染
            verify(h.controllerRegistrar).unregisterControllers("ext-sched");              // controller 足迹回滚（无注册项仍被调）
            verify(h.webRegistrar).unregister(eq("ext-sched"), any());                     // web 贡献回滚
        }
    }

    @Test
    @DisplayName("运行期 start 时插件 start() 抛异常：schedule 贡献随足迹一并回滚，落 STOPPED")
    void pluginStartFailureRollsBackScheduleContributions() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();
            h.service.stop("ext-sched");
            h.plugin.failStart = true;

            h.service.start("ext-sched"); // 足迹重建（schedule 再注册成功）后 plugin.start() 抛异常 → 回滚

            assertThat(h.plugin.startCount).isEqualTo(1);
            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.STOPPED);
            assertThat(h.sourceRegistry.resolve("ext-source")).isEmpty();  // schedule 贡献被回滚
            assertThat(h.runnerRegistry.resolve("ext-kind")).isEmpty();
        }
    }

    @Test
    @DisplayName("quiesce 注销 schedule 来源 + 执行器：解析均落空（残留任务即 SOURCE_UNAVAILABLE 干净挂起、数据不删），子 context 仍在")
    void quiesceUnregistersScheduleContributions() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();
            assertThat(h.sourceRegistry.resolve("ext-source")).isPresent();
            assertThat(h.runnerRegistry.resolve("ext-kind")).isPresent();

            h.service.quiesce("ext-sched");

            // 来源 / 执行器随 quiesce 注销 → ScheduleExecutor 解析不到 → 残留任务即 SOURCE_UNAVAILABLE 干净挂起、数据不删
            //（「解析落空 → SOURCE_UNAVAILABLE 且不读 cookie / 不发现 / 不派发 / 不删数据」链路由 ScheduleExecutorSourceResolutionTest 钉死）。
            assertThat(h.sourceRegistry.resolve("ext-source")).isEmpty();
            assertThat(h.sourceRegistry.resolve("EXT_SOURCE")).isEmpty(); // legacy 名同样解析落空
            assertThat(h.runnerRegistry.resolve("ext-kind")).isEmpty();
            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.QUIESCED);
            // quiesce 仅停新派发 + 清退在途、不拆服务足迹：子 context 仍在（待 stop 才关闭）。
            assertThat(h.service.contextFor("ext-sched")).isPresent();
        }
    }

    @Test
    @DisplayName("quiesce 后 stop 幂等：schedule 注销不重复出错、解析仍落空，阶段经 QUIESCED 落 STOPPED、子 context 关闭")
    void quiesceThenStopKeepsScheduleUnregisteredIdempotently() {
        try (ScheduleHarness h = new ScheduleHarness()) {
            h.service.startAll();

            h.service.quiesce("ext-sched");
            assertThat(h.sourceRegistry.resolve("ext-source")).isEmpty();

            // stop after quiesce：tearDownServing 再注销 schedule 为安全 no-op（幂等、不二次出错），其余足迹照常拆除。
            h.service.stop("ext-sched");

            assertThat(h.sourceRegistry.resolve("ext-source")).isEmpty();
            assertThat(h.runnerRegistry.resolve("ext-kind")).isEmpty();
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
            assertThat(h.sourceRegistry.resolve("ext-source")).isEmpty();
            assertThat(h.runnerRegistry.resolve("ext-kind")).isEmpty();

            h.service.reload("ext-sched"); // QUIESCED → stop → start

            assertThat(h.sourceRegistry.resolve("ext-source")).isPresent();
            assertThat(h.sourceRegistry.resolve("EXT_SOURCE")).isPresent();
            assertThat(h.runnerRegistry.resolve("ext-kind")).isPresent();
            assertThat(h.service.phase("ext-sched")).contains(PluginRuntimePhase.STARTED);
        }
    }

    // ============================ 夹具 ============================

    private static PluginLifecycleService realService(ApplicationContext parent, List<PluginContextModule> modules) {
        return new PluginLifecycleService(parent, runtimeReturning(modules), new PluginApplicationContextFactory(),
                emptyControllerRegistrar(), emptyWebRegistrar(), emptyScheduleRegistrar(), new PluginRegistry(List.of()),
                new PluginLifecycleState(), new QueueOperationRegistry(List.of()), new PluginStreamRegistry());
    }

    private static PluginScheduleContributionRegistrar emptyScheduleRegistrar() {
        return new PluginScheduleContributionRegistrar(
                new ScheduledSourceRegistry(new PluginRegistry(List.of())), new ScheduledWorkRunnerRegistry(List.of()));
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
                new WebI18nBundleRegistry(empty), new NavigationRegistry(empty),
                new WebUiSlotRegistry(empty), userscripts, scripts);
    }

    /** 记录 start() / stop() 调用次数的功能插件夹具（验证生命周期被调、幂等，{@code failStart} 可令 start() 抛异常）。 */
    private static final class RecordingPlugin implements PixivFeaturePlugin {
        private final String id;
        private int startCount;
        private int stopCount;
        private boolean failStart;
        private String queueType; // 非空时声明对应作品类型（验证 quiesce / 卸载时排空其在途队列）
        private List<ScheduledSourceProvider> scheduledSources = List.of(); // 非空时贡献计划任务来源（验证 schedule 热插拔）

        RecordingPlugin(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public List<QueueTypeContribution> queueTypes() {
            return queueType == null ? List.of()
                    : List.of(new QueueTypeContribution(id, queueType, id + ":label", 10, null));
        }

        @Override
        public List<ScheduledSourceProvider> scheduledSources() {
            return scheduledSources;
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

    /**
     * 记录 clearAll 调用并在排空时放行 {@code drained} latch 的队列操作夹具：模拟「在途下载被取消」——
     * 一个阻塞等待该 latch 的下载线程在 clearAll 后被放行（即被取消、干净退出）。
     */
    private static final class RecordingQueueOperations implements QueueOperations {
        private final String type;
        int clearAllCount;
        final CountDownLatch drained = new CountDownLatch(1);

        RecordingQueueOperations(String type) {
            this.type = type;
        }

        @Override
        public String queueType() {
            return type;
        }

        @Override
        public int clearAll() {
            clearAllCount++;
            drained.countDown();
            return 3;
        }

        @Override
        public int clearForOwner(String ownerUuid) {
            return 0;
        }
    }

    /** 记录关闭次数的推流夹具（验证 quiesce / 卸载时被 closeForPlugin 关闭）。 */
    private static final class RecordingStream implements PluginStream {
        int closedCount;

        @Override
        public void closeUnavailable() {
            closedCount++;
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

    // --- schedule 贡献夹具 ---

    /**
     * 真实父 + 真实子 context + 真实调度注册中心 + mock web/controller 注册器的装置：验证 schedule 来源 / 执行器随
     * 插件 start/stop/unload/reload 热插拔与注册失败回滚。{@code ext-sched} 贡献一个来源（规范 {@code ext-source} +
     * legacy {@code EXT_SOURCE}）且其子 context 含一个执行器（kind={@code ext-kind}）；可注入预置执行器制造 kind 冲突。
     */
    private static final class ScheduleHarness implements AutoCloseable {
        final AnnotationConfigApplicationContext parent =
                new AnnotationConfigApplicationContext(ParentCoreConfig.class);
        final PluginControllerRegistrar controllerRegistrar = mock(PluginControllerRegistrar.class);
        final PluginWebContributionRegistrar webRegistrar = mock(PluginWebContributionRegistrar.class);
        final PluginRegistry registry = mock(PluginRegistry.class);
        final PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
        final PluginLifecycleState state = new PluginLifecycleState();
        final RecordingPlugin plugin = new RecordingPlugin("ext-sched");
        final PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, ScheduleHarness.class.getClassLoader());
        final ScheduledSourceRegistry sourceRegistry = new ScheduledSourceRegistry(new PluginRegistry(List.of()));
        final ScheduledWorkRunnerRegistry runnerRegistry;
        final PluginScheduleContributionRegistrar scheduleRegistrar;
        final PluginLifecycleService service;

        ScheduleHarness(ScheduledWorkRunner... preexistingRunners) {
            plugin.scheduledSources = List.of(sourceProvider("ext-source", "EXT_SOURCE"));
            runnerRegistry = new ScheduledWorkRunnerRegistry(List.of(preexistingRunners));
            scheduleRegistrar = new PluginScheduleContributionRegistrar(sourceRegistry, runnerRegistry);
            PluginContextModule module = new PluginContextModule(
                    "ext-sched", ScheduleHarness.class.getClassLoader(), List.of(ScheduleContribConfig.class));
            when(runtime.inspectContextModules()).thenReturn(List.of(module));
            when(registry.registeredPlugins()).thenReturn(List.of(registered));
            service = new PluginLifecycleService(parent, runtime, new PluginApplicationContextFactory(),
                    controllerRegistrar, webRegistrar, scheduleRegistrar, registry, state,
                    new QueueOperationRegistry(List.of()), new PluginStreamRegistry());
        }

        @Override
        public void close() {
            parent.close();
        }
    }

    private static ScheduledSourceProvider sourceProvider(String type, String... legacy) {
        return new ScheduledSourceProvider() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Set<String> legacyTypeNames() {
                return Set.of(legacy);
            }
        };
    }

    private static ScheduledWorkRunner workRunner(String kind) {
        return new ScheduledWorkRunner() {
            @Override
            public String kind() {
                return kind;
            }

            @Override
            public boolean download(ScheduledWork work, ScheduledWorkSettings settings, String cookie) {
                return true;
            }
        };
    }

    /** 子 context 装配定义：核心服务消费 Bean + 一个 kind=ext-kind 的执行器（验证从子 context 发现执行器）。 */
    @Configuration
    static class ScheduleContribConfig {
        @Bean
        PluginBean pluginBean(CoreApiService coreService) {
            return new PluginBean(coreService);
        }

        @Bean
        ScheduledWorkRunner extWorkRunner() {
            return workRunner("ext-kind");
        }
    }
}
