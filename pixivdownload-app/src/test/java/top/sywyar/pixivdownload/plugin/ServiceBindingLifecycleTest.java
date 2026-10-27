package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.plugin.lifecycle.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.quiesce.PluginRuntimeTaskQuiescer;
import top.sywyar.pixivdownload.plugin.registry.*;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.context.*;
import top.sywyar.pixivdownload.plugin.runtime.stream.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry;
import top.sywyar.pixivdownload.plugin.web.registration.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("依赖绑定经过真实 child context 与插件发布生命周期")
class ServiceBindingLifecycleTest {
    public interface Echo { String echo(String value); }
    private static final ServiceKey<Echo> KEY = new ServiceKey<>("lifecycle-contract", Echo.class);
    private static final class Control {
        ServiceBinding binding;
        ServiceBindingScope scope;
        final AtomicInteger activated = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final AtomicBoolean failClose = new AtomicBoolean();
        PluginLifecycleService lifecycle;
    }
    @Configuration(proxyBeanMethods = false)
    static class ConsumerConfiguration {
        @Bean Object bind(PluginServices services, Control control) {
            control.binding = services.bind(List.of(KEY), scope -> {
                assertThat(control.lifecycle.phase("consumer")).contains(PluginRuntimePhase.STARTED);
                assertThat(scope.require(KEY).echo("ready")).isEqualTo("ready");
                assertThatThrownBy(() -> control.lifecycle.stop("provider"))
                        .isInstanceOf(ServiceAccessException.class).hasMessage("NOT_ACTIVE");
                control.scope = scope;
                control.activated.incrementAndGet();
                scope.acquire(() -> () -> {
                    assertThat(control.lifecycle.contextFor("consumer")).isPresent();
                    if (control.failClose.get()) throw new IllegalStateException("private");
                    control.closed.incrementAndGet();
                });
            });
            return new Object();
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class ProviderConfiguration { @Bean Echo echo() { return value -> value; } }

    private static PixivFeaturePlugin plugin(String id) {
        return new PixivFeaturePlugin() {
            public String id() { return id; }
            public String displayName() { return "test.name"; }
            public String description() { return "test.description"; }
            public PluginKind kind() { return PluginKind.FEATURE; }
            public List<ServiceKey<?>> serviceImports() { return id.equals("consumer") ? List.of(KEY) : List.of(); }
            public List<ServiceExport<?>> serviceExports() {
                return id.equals("provider") ? List.of(new ServiceExport<>(KEY, "echo")) : List.of();
            }
        };
    }

    private static final class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
        final Queue<Runnable> work = new ConcurrentLinkedQueue<>();
        final Control control = new Control();
        final PluginLifecycleService lifecycle;
        Fixture() {
            parent.registerBean(Control.class, () -> control); parent.refresh();
            var consumer = new PluginRegistry.RegisteredPlugin(plugin("consumer"), PluginSource.EXTERNAL,
                    getClass().getClassLoader(), "consumer", 1);
            var provider = new PluginRegistry.RegisteredPlugin(plugin("provider"), PluginSource.EXTERNAL,
                    getClass().getClassLoader(), "provider", 1);
            var plugins = mock(PluginRegistry.class);
            when(plugins.registeredPlugins()).thenReturn(List.of(consumer, provider));
            when(plugins.featureStarted(any())).thenReturn(true);
            var runtime = mock(PluginRuntimeManager.class);
            when(runtime.inspectContextModules()).thenReturn(List.of(
                    new PluginContextModule("consumer", getClass().getClassLoader(), List.of(ConsumerConfiguration.class)),
                    new PluginContextModule("provider", getClass().getClassLoader(), List.of(ProviderConfiguration.class))));
            var state = new PluginLifecycleState(); var tasks = new PluginRuntimeTaskRegistry();
            var streams = new PluginStreamRegistry(); var queues = new QueueOperationRegistry(List.of());
            var schedules = mock(PluginScheduleContributionRegistrar.class);
            var invocations = new ExternalCapabilityInvocationRegistry(state);
            invocations.configureBindings(tasks, work::add);
            var registrar = new PluginCapabilityContributionRegistrar(List.of(), List.of(), List.of(), invocations);
            var web = mock(PluginWebContributionRegistrar.class);
            when(web.currentHandle(any())).thenReturn(Optional.of(mock(PluginWebContributionHandle.class)));
            when(web.prepare(any())).thenReturn(mock(PluginWebContributionRegistrar.PreparedWebContribution.class));
            when(web.commit(any())).thenReturn(mock(PluginWebContributionHandle.class));
            lifecycle = new PluginLifecycleService(parent, runtime, new PluginApplicationContextFactory(streams, tasks),
                    mock(PluginControllerRegistrar.class), web, schedules,
                    new PluginRuntimeTaskQuiescer(schedules, streams, queues, tasks), registrar, plugins, state);
            control.lifecycle = lifecycle;
        }
        void flush() { for (Runnable r; (r = work.poll()) != null;) r.run(); }
        public void close() { parent.close(); }
    }

    @Test @DisplayName("发布后激活，关闭失败保留双方 context，重试和同代重启建立新绑定")
    void lifecyclePublicationAndCleanup() {
        try (var f = new Fixture()) {
            f.lifecycle.startAll();
            assertThat(f.control.activated).hasValue(0); f.flush();
            assertThat(f.control.binding.state()).isEqualTo(ServiceBinding.State.ACTIVE);
            var old = f.control.scope.require(KEY);
            var provider = f.lifecycle.contextFor("provider").orElseThrow();
            f.control.failClose.set(true);
            assertThatThrownBy(() -> f.lifecycle.stop("provider")).isInstanceOf(PluginLifecycleException.class);
            f.flush();
            assertThat(f.control.binding.state()).isEqualTo(ServiceBinding.State.CLOSE_FAILED);
            assertThat(provider.isActive()).isTrue();
            assertThatThrownBy(() -> old.echo("old")).hasMessage("UNAVAILABLE");
            f.control.failClose.set(false); f.control.binding.retry(); f.flush();
            f.lifecycle.stop("provider"); assertThat(provider.isActive()).isFalse();
            f.lifecycle.start("provider"); f.flush();
            assertThat(f.control.activated).hasValue(2);
            assertThatThrownBy(() -> old.echo("old")).hasMessage("UNAVAILABLE");
            assertThatThrownBy(() -> f.lifecycle.stop("consumer")).isInstanceOf(PluginLifecycleException.class);
            f.flush(); f.lifecycle.stop("consumer");
            assertThat(f.control.binding.state()).isEqualTo(ServiceBinding.State.CLOSED);
            f.lifecycle.restart("provider"); f.flush();
            assertThat(f.control.activated).hasValue(2);
            f.lifecycle.stopAll();
        }
    }

    @Test @DisplayName("核心关闭等待真实回调退出时释放生命周期锁，允许回调查询 context")
    void shutdownAllowsCallbackToExit() throws Exception {
        try (var f = new Fixture()) {
            f.lifecycle.startAll(); f.flush();
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            Runnable callback = f.control.scope.guard(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                assertThat(f.lifecycle.contextFor("consumer")).isPresent();
            });
            Thread running = new Thread(callback); running.setDaemon(true); running.start();
            Thread shutdown = new Thread(() -> {
                try { f.lifecycle.stopAll(); } catch (Throwable e) { failure.set(e); }
            }); shutdown.setDaemon(true);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); shutdown.start();
                release.countDown(); running.join(5000);
                assertThat(running.isAlive()).isFalse();
                // 等待撤回通知进入确定性的执行队列，再执行清理。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (f.work.isEmpty() && shutdown.isAlive() && System.nanoTime() < deadline) Thread.onSpinWait();
                f.flush(); shutdown.join(5000);
                assertThat(shutdown.isAlive()).isFalse(); assertThat(failure.get()).isNull();
                assertThat(f.control.closed).hasValue(1);
                assertThat(f.lifecycle.contextFor("consumer")).isEmpty();
            } finally { release.countDown(); running.join(5000); f.flush(); shutdown.join(5000); }
        }
    }
}
